# 시나리오 16. 상품 테이블 CDC: products 의 변경이 product.cdc.products 로 나간다

상품 검색(후속 4, [ADR 0010](../adr/0010-product-search-cqrs-table-cdc.md))의 읽기 모델은 product-service 의 `products` 테이블을 Debezium 으로 복제해 만든다. outbox 릴레이(ADR 0004)와 같은 Debezium 이지만, 여기서는 **테이블 CDC(데이터 복제)** 로 쓴다: product-service 코드는 바뀌지 않고, DB 에 커밋된 변경이 그대로 나간다(직접 SQL 로 바꾼 것 포함).

## 구성

| | |
|---|---|
| 커넥터 | `product-cdc` (compose `infra/connect/product-cdc.json`, k8s `KafkaConnector product-cdc`). `product_svc` 계정으로 `product_db` 의 WAL 을 읽는다(replication 권한, slot `product_cdc_slot`, publication `product_cdc_pub`) |
| 대상 | `public.products` 의 열 6개만(`column.include.list`): `id`, `name`, `description`, `price`, `on_sale`, `updated_at` |
| 처음 적재 | `snapshot.mode=initial`: 커넥터를 처음 등록하면 기존 행(시드 1~5 포함)을 먼저 보낸다 |
| relay 모드 | outbox relay 가 스케줄러 모드여도 이 커넥터는 등록한다(검색 읽기 모델은 CDC 로만 만든다) |

## 토픽 계약 `product.cdc.products` (product-service 소유)

테이블 CDC 는 테이블 열이 곧 계약이 된다. 그래서 이 토픽 모양을 product-service 의 계약으로 정하고, **열은 추가만** 한다(이름·타입·의미를 바꾸려면 새 열, ADR 0005 와 같은 규칙). 포함할 열을 늘리면 커넥터의 `column.include.list` 도 같이 바꾼다.

| 부분 | 내용 |
|---|---|
| 키 | 상품 id (문자열, 예: `4`). 상품 단위 순서 (AGENTS.md 키 규칙의 예외) |
| 값 | 변경 **후** 행, 평평한 JSON (`ExtractNewRecordState`). 예: `{"id":4,"name":"…","description":"…","price":12100,"on_sale":true,"updated_at":"2026-11-25T05:00:00.123456Z"}` |
| 헤더 `__lsn` | 원본 WAL 위치. 같은 상품의 뒤 변경일수록 크다 → 검색 쪽이 외부 버전으로 쓴다(재전달·순서 뒤바뀜 무시) |
| 헤더 `__source_ts_ms` | 원본 커밋 시각(ms). 색인 지연 지표에 쓴다 |
| 삭제 | 값이 null 인 tombstone. 지금은 상품을 지우지 않는다(판매 중지 = `on_sale:false` 변경) |
| DLT | `product.cdc.products.DLT` (search-service 가 처리할 수 없는 메시지: 본문·키·헤더가 계약과 다름, 문서 거절) |

## 재현

```bash
docker compose -f infra/docker-compose.yml up -d --wait
# 서비스 기동 (product-service 포함) 뒤
CONNECTORS=product-cdc bash scripts/register-connectors.sh   # debezium 모드면 인자 없이 전부
bash scripts/smoke-product-cdc.sh
```

k8s(kind): `KAFKA_EXEC="kubectl -n commerce exec -i commerce-dual-role-0 --" KAFKA_BOOTSTRAP=localhost:9092 bash scripts/smoke-product-cdc.sh`

## 확인하는 것 (`scripts/smoke-product-cdc.sh`, CI e2e 두 relay 모드·k8s kind)

| | 기대 |
|---|---|
| 16a 처음 적재 | 시드 상품 1 이 키 `1` 로 토픽에 있다 |
| 16b 가격 변경 | 관리자가 상품 4 의 가격을 바꾸면 키 `4`, 변경 후 행이 나간다. 열 6개만(`created_at` 없음), Debezium 봉투(`before`·`after`·`op`) 없음, 헤더 `__lsn`·`__source_ts_ms` |
| 16c 순서 | 다음 변경의 `__lsn` 이 더 크다 |

## 결과

```
== 16a. 처음 적재: 시드 상품 1 이 키 "1" 로 있다
  ok  상품 1 스냅샷 있음
== 16b. 가격 변경 → 변경 후 행이 키 = 상품 id 로
  ok  키 4, 가격 12100, 열 6개만, 헤더 __lsn=57798856
== 16c. 다음 변경은 __lsn 이 더 크다
  ok  __lsn 57798856 → 57800016
시나리오 16 통과
```

(CI e2e scheduler 모드 로그, PR #82. `__lsn` 값은 실행마다 다르다)

## 한계

- 테이블 열이 계약이라 product-service 가 `products` 열의 이름·타입을 바꾸면 검색이 깨질 수 있다 → 위 계약 규칙, 4-2b 의 계약 테스트
- 커넥터가 멈춘 동안의 변경은 slot 이 WAL 을 붙잡아 두었다가 재개하면 이어서 보낸다(시나리오 5 와 같음). 그동안 검색 결과는 늦다
