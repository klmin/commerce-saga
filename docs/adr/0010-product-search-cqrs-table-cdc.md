# ADR 0010. 상품 검색 CQRS: Debezium 테이블 CDC → search-service → Elasticsearch

- 상태: 채택
- 날짜: 2026-11-25
- 번호: 브리프 계획 단계에서 이 주제에 0010 을 비워 두었다(0009 Redis 범위는 아직 비어 있음)

## 배경

상품의 주인은 product-service(Postgres, ADR 0016)다. 목록·상세·여러 건 조회는 product 가 직접 서빙하지만, 검색어(부분 일치)·가격 범위·정렬 같은 검색은 Postgres 로 하기 어렵고 쓰기 DB 에 부하를 준다. 브리프는 후속 4 를 "쓰기는 Postgres, 검색은 Elasticsearch(CQRS)" 로 잡았고, 이 저장소에서 Debezium 을 outbox 릴레이(ADR 0004)뿐 아니라 **테이블 CDC(데이터 복제)** 로도 쓰는 예로 삼았다.

## 고려한 안

| | A. 테이블 CDC | B. outbox 이벤트(`product.events`) | C. 검색 때 product 동기 조회 |
|---|---|---|---|
| product 코드 변경 | 없음 (커넥터 설정만) | 상품 변경마다 outbox INSERT | 검색 API 를 product 에 |
| 계약 | 테이블 열이 곧 계약 (내부 스키마가 샌다) | 이벤트 스키마 (의도한 계약) | API |
| 빠뜨림 | 없음 (DB 변경이면 다 나간다, 직접 SQL 포함) | 코드가 outbox 를 빠뜨리면 샌다 | - |
| 이 저장소에서 보여 주는 것 | Debezium 의 두 번째 쓰임새 | 3단계에서 이미 보임 | 검색 엔진 없음 |

- A 의 약점(내부 스키마가 계약이 됨)은 실무에서도 테이블 CDC 를 쓸 때의 대표적인 비용이다. 이 저장소에서는 범위를 줄여 감수한다(아래 결정 2)
- C 는 Postgres `LIKE`·트라이그램으로 할 수 있지만 쓰기 DB 와 검색 부하가 섞이고, CQRS 를 보여 주지 못한다

## 결정

1. **search-service** 를 새로 만든다. Elasticsearch 가 이 서비스의 저장소다(다른 서비스는 ES 에 접근하지 않는다). 하는 일은 둘: CDC 토픽을 소비해 색인, 검색 API(`GET /search/products`, Gateway `/api/search/**`, 로그인 없이 공개)
2. product_db 의 `products` 테이블에 Debezium Postgres 커넥터(테이블 CDC). 내부 스키마가 새는 범위를 줄인다
   - `column.include.list` 로 검색에 필요한 열만(id, 이름, 설명, 가격, 판매 여부, 수정 시각)
   - `ExtractNewRecordState` 로 변경 후 행만, 원본 LSN·커밋 시각은 헤더로
   - 토픽 `product.cdc.products` 는 **product-service 소유의 계약**으로 문서화한다. 열은 추가만(ADR 0005 와 같은 규칙), 이름·타입을 바꾸려면 새 열
3. 메시지 키 = 상품 id. 주문과 상관없는 토픽이라 AGENTS.md 키 규칙(orderId, `auth.events` 는 userId)에 예외로 추가한다. 상품 단위 순서만 필요하다
4. 멱등·순서: ES 문서 id = 상품 id, **외부 버전 = 원본 LSN**(`version_type=external`). 같은 변경이 다시 와도(at-least-once, ADR 0006) 같은 버전이라 무시되고, 늦게 온 옛 변경은 ES 가 거절한다. `processed_message` 표는 두지 않는다
5. 상품은 지우지 않고 판매 중지한다(4-1a). 판매 중지 문서는 남기고 검색에서 거른다. tombstone 이 오면 문서를 지운다
6. 처음 적재는 커넥터 스냅샷(`snapshot.mode=initial`). 색인 구조를 바꿀 때는 별칭(`products` → `products-v{n}`) + 새 색인 + Debezium 증분 스냅샷으로 다시 채운 뒤 별칭을 옮긴다(절차는 문서·스크립트)
7. 한글은 ngram 분석기(플러그인 없음, 공식 이미지 그대로). 형태소 분석(nori)은 플러그인을 넣은 이미지를 따로 빌드해야 해서 하지 않는다
8. ES 장애: 검색만 503. 상품 목록·주문은 영향 없다. 색인 소비자는 제자리 재시도(ADR 0012)로 ES 가 돌아올 때까지 기다렸다가 따라잡는다. 본문·헤더가 이상한 메시지는 DLT
9. 배포: Elasticsearch 공식 이미지 단일 노드(로컬·CI 는 보안 끔, 힙 512MB), Helm 차트에 StatefulSet. ECK 오퍼레이터는 쓰지 않는다. 클라이언트는 REST 수준 기능만 써서 OpenSearch 로 바꿀 여지를 둔다
10. 색인 지연(지금 − 원본 커밋 시각) 지표와 경보를 둔다

## 결과와 위험

- 좋은 점: product 코드를 고치지 않고 검색 읽기 모델을 만든다. 쓰기 DB 와 검색 부하가 나뉜다. 직접 SQL 로 바꾼 것도 검색에 반영된다
- 비용: ES 메모리(로컬 약 1GB), 서비스·커넥터·토픽이 하나씩 는다
- 위험: products 테이블 열을 바꾸면 검색이 깨질 수 있다 → 토픽 계약 문서와 search-service 의 계약 테스트로 막는다. 검색 결과는 최종 일관성이라 수 초 늦을 수 있다 → 주문 때 가격은 ADR 0016 이 다시 확인한다
- 다시 검토할 때: product 의 변경을 검색 외 소비자(정산·추천)도 알아야 하면 `product.events`(outbox)를 추가하고 search 도 그쪽으로 옮기는 것을 검토한다
