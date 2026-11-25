# 시나리오 17. 상품 검색: CDC → search-service → Elasticsearch

상품 검색은 product-service 의 `products` 테이블을 Debezium 으로 복제한 토픽(`product.cdc.products`, 시나리오 16)을 search-service 가 받아 Elasticsearch 에 색인하고, 공개 검색 API 로 낸다(CQRS, [ADR 0010](../adr/0010-product-search-cqrs-table-cdc.md)). 쓰기(product-service, Postgres)와 검색(search-service, ES)이 나뉘고, 검색 결과는 수 초 늦을 수 있다(최종 일관성). 가격은 주문 때 다시 확인하므로(ADR 0016) 돈은 맞다.

## 구성

| | |
|---|---|
| 색인 | 별칭 `products` → 실제 색인 `products-v1`. search-service 가 처음 쓸 때 별칭이 없으면 만든다. 매핑 strict |
| 멱등·순서 | 문서 id = 상품 id, **외부 버전 = 헤더 `__lsn`**(`version_type=external`). 저장된 버전 이하이면 ES 가 409 → 건너뜀. 같은 변경 재전달, 늦게 온 옛 변경 모두 색인을 되돌리지 못한다. `processed_message` 표가 필요 없다 |
| 삭제 | tombstone 이면 문서를 지우지 않고 **삭제 표시 문서**(`deleted:true`, 같은 외부 버전)로 바꿔 검색에서 뺀다. ES 는 지운 문서의 버전을 `index.gc_deletes` 동안만 기억하므로, 진짜로 지우면 그 뒤 DLT 재처리된 옛 행이 지운 상품을 되살릴 수 있다. 버전 없는 tombstone 은 가장 큰 버전으로 표시(영구) |
| 한글 | ngram(1~2글자) 분석기. "마우" → "무선 마우스", "키캡" → "한정판 키캡"(키보드는 아님: 모든 글자 조각이 있어야 함). 형태소 분석(nori)은 쓰지 않는다 |
| 검색 API | `GET /api/search/products` (로그인 없이): `q`, `minPrice`, `maxPrice`, `onSaleOnly`(기본 true), `sort`(relevance·price-asc·price-desc·newest), `page`, `size`(≤100, 앞 10,000건까지) |
| 장애 | ES 가 멈추면 검색만 503. 상품 목록·상세·주문은 product-service 라 그대로. 색인 소비자는 ES 가 돌아올 때까지 제자리 재시도(ADR 0012) → 돌아오면 멈춘 동안의 변경을 차례로 반영 |
| 영구 오류 | 본문·키·헤더가 계약과 다르거나 ES 가 문서를 거절(400)하면 `product.cdc.products.DLT` (`scripts/dlt.sh`) |
| 지표 | `search_indexing_lag_seconds`(마지막 메시지의 원본 커밋 → 색인), `search_indexed_total{result=applied|skipped}`, 소비자 lag 은 기존 kafka-exporter 경보 |

## 재현

```bash
docker compose -f infra/docker-compose.yml up -d --wait      # elasticsearch 포함
# product-service, search-service(8089), gateway 기동 뒤
CONNECTORS=product-cdc bash scripts/register-connectors.sh
bash scripts/smoke-search.sh
```

k8s(kind): `KAFKA_EXEC="kubectl -n commerce exec -i commerce-dual-role-0 --" KAFKA_BOOTSTRAP=localhost:9092 SKIP_OUTAGE=true bash scripts/smoke-search.sh` (ES 중단 17e 는 compose 에서만)

## 확인하는 것 (`scripts/smoke-search.sh`, CI e2e 두 relay 모드·k8s kind)

| | 기대 |
|---|---|
| 17a 처음 적재 | 스냅샷으로 들어온 시드 상품 2(무선 마우스)가 "마우" 로 나온다 |
| 17b 가격 변경 | 관리자가 가격을 바꾸면 몇 초 안에 검색 결과 가격이 바뀐다 (걸린 초를 출력) |
| 17c 판매 중지 | 기본 검색에서 빠지고 `onSaleOnly=false` 로는 보인다. 판매 재개하면 다시 나온다 |
| 17d 옛 변경 | 토픽에 `__lsn:1`, 가격 1 인 옛 변경을 직접 넣어도 다음 변경이 반영된 뒤 가격이 1 로 되돌아가지 않는다 |
| 17e ES 중단 (compose) | 검색 503, 상품 목록 200, 가격 변경 성공. ES 재개 뒤 멈춘 동안의 가격이 검색에 반영된다 |

## 결과

CI e2e 로그로 채운다(이 PR 의 CI 결과).

## 한계

- 검색 결과는 최종 일관성이다(보통 수 초). 커넥터나 search-service 가 멈추면 그동안 늦어진다
- ES 단일 노드라 고가용성이 없다. 색인은 PVC 에 둔다. 색인을 잃으면 소비 오프셋은 이미 커밋돼 있어 저절로 다시 채워지지 않는다 → 다시 색인(새 색인 + 커넥터 증분 스냅샷 + 별칭 이동, ADR 0010 결정 6)
- ngram 이라 형태소 검색이 아니다(조사·어미를 구분하지 않음). 자동완성·오타 교정·집계는 없다
- 회로가 열린 듯한 장애(느린 ES)에 대한 서킷 브레이커는 두지 않았다. 검색은 타임아웃(연결 2초·응답 5초) 뒤 503
