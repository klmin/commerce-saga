# 시나리오 10. 같은 Idempotency-Key로 주문 연속 요청 → 주문 1건

결제 버튼을 두 번 누르거나, 새로고침하거나, 응답을 못 받아 다시 보내도 주문(그리고 Saga·포인트 차감·결제)은 한 번만 일어나는지 확인한다. 같은 키에 다른 내용을 보내면 거부한다. 프론트 화면이 쓸 내 주문·결제 목록도 함께 확인한다.

메시지 쪽 멱등(시나리오 3, ADR 0006)이 "같은 메시지가 두 번 와도 한 번"이라면, 이 시나리오는 그 앞단 "같은 요청이 두 번 와도 한 번"이다.

## 설계

| 항목 | 결정 |
|---|---|
| 헤더 | `Idempotency-Key` 선택. 없으면 지금처럼 요청마다 주문 생성. 1~100자 보이는 ASCII(공백·제어 문자 X), UUID 권장 |
| 범위 | 사용자별 `(user_id, idempotency_key)` 유니크. 다른 사용자가 같은 키를 써도 서로 영향 없음 |
| 같은 키 + 같은 본문 | 새로 만들지 않고 처음 주문을 `200`으로 (`Location`, `Idempotent-Replayed: true`). 처음 요청은 `201`. 커맨드도 다시 보내지 않는다 |
| 같은 키 + 다른 본문 | `422` Problem Details. 본문 비교는 줄(상품 id 순 정렬)·수량·단가·사용 포인트의 SHA-256 |
| 저장 | order-service `order_idempotency_keys (user_id, idempotency_key)` PK + `order_id` (`orders` FK, 커밋 때 검사) — Flyway V6 |
| 동시 요청 | 주문 트랜잭션 첫 단계에서 `insert … on conflict do nothing`으로 키를 선점한다. 늦은 쪽은 앞 트랜잭션이 끝날 때까지 PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건 → 그 주문을 읽어 돌려주고, 롤백하면 1건 → 이어받아 주문을 만든다. `processed_message`와 같은 선점 방식 |
| 원자성 | 키 선점 + 주문 + Saga + 재고 예약 커맨드(outbox) = 한 트랜잭션. 주문이 롤백되면 키도 없다 (키만 남아 다음 재시도를 막는 일이 없음) |
| 보존 기간 | 정리 작업 없음 (한계). 키는 주문과 함께 남는다 |

내 주문·결제 목록은 알림 목록과 같은 커서 페이징이다: `GET /api/orders?before=<orderId>&size=`, `GET /api/payments?before=<paymentId>&size=` (`size` 1~50, 기본 20, 최신순). id가 UUIDv7이라 id 순서가 생성 순서와 같다. 경로에 사용자 id를 받지 않고 토큰의 `sub`로만 거른다(남의 목록을 볼 방법이 없음). 인덱스 `(user_id, id)` (order·payment V6).

## 재현

1. 인프라, 다섯 서비스, auth-service, gateway를 띄운다
2. `scripts/smoke-idempotency.sh` (사용자 3, Gateway 경유)

CI `e2e`가 두 relay 모드에서 Gateway 시나리오(9f~9i) 다음에 돈다.

## 기대 결과

| 단계 | 기대 |
|---|---|
| 10a | 같은 키로 두 번 → `201` 다음 `200`, 같은 `orderId`, `Idempotent-Replayed: true` |
| 10b | 같은 키로 동시에 5번 → 모두 같은 `orderId`, `201` 한 번 + `200` 네 번. 내 주문 목록 맨 위 두 건이 10b·10a 주문(다른 주문이 생기지 않음) |
| 10c | 10a 키 + 다른 수량 → `422`, 주문 안 생김 |
| 10d | 두 주문 모두 `COMPLETED`, 포인트는 2건 × 100만 차감 (재요청은 Saga를 다시 시작하지 않음) |
| 10e | 주문 목록 커서 다음 쪽이 10a 주문, 결제 목록에 두 주문 결제가 하나씩, 사용자 4의 주문·결제 목록에는 안 보임 |

테스트로도 확인한다: `OrderServiceTest`(같은 키·다른 본문·줄 순서·사용자 범위·키 형식), `OrderQueryAndIdempotencyIntegrationTest`(실제 Postgres에서 같은 키 8개 동시 요청 → 주문·Saga·키 각 1행, 목록 커서), `PaymentSummaryPersistenceAdapterTest`, 컨트롤러 테스트(201/200/422/400).

## 실제 결과

CI `e2e` 결과는 머지 후 다음 PR에서 채운다.

## 한계

- 키 보존 기간 정리 작업이 없다. 오래된 키를 지우려면 주문과 별도로 기간이 지난 행을 지우는 작업이 필요하다
- 키는 "주문 생성"에만 있다. 다른 쓰기 API(로그아웃 등)는 원래 멱등하거나 재시도해도 결과가 같다
- 처음 요청이 아직 처리 중일 때 같은 키가 오면 기다렸다가(행 락) 결과를 받는다. 오래 걸리는 처리라면 `409`로 바로 돌려주는 방식도 있지만, 주문 생성은 짧은 트랜잭션이라 기다리게 했다
