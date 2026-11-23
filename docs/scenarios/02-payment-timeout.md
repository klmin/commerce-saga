# 시나리오 2. 결제 타임아웃 → PG 조회로 확정 / 오래 걸리면 결제 취소 + 늦은 승인 자동 환불

결제는 응답이 없다고 돈이 안 나간 게 아니다. PG 응답이 늦을 때 바로 실패로 보고 보상하면 **돈은 나갔는데 주문은 취소**될 수 있다. 이 시나리오는 그런 상황에서 결과를 조회로 확정하는지, Saga가 결제를 포기한 뒤 PG가 늦게 승인해도 돈이 남지 않는지(자동 환불) 확인한다. 정책은 [ADR 0007](../adr/0007-saga-timeout.md)의 결제 부분이다.

## 재현

1. 인프라와 네 서비스를 띄운다. 타임아웃을 짧게 둔다 (CI `e2e`와 같은 값)
   - order-service: `SAGA_STEP_TIMEOUT=10s SAGA_PAYMENT_MAX_RESENDS=2 SAGA_TIMEOUT_SCAN_INTERVAL=1s` → 결제를 약 30초 기다린 뒤 포기
   - payment-service: `PG_CLIENT_TIMEOUT=3s`(기본) `PAYMENT_RECONCILE_INTERVAL=1s`
2. `scripts/smoke-payment-timeout.sh`: 가짜 PG 장애 주입(`PUT /admin/payment-faults`, `DELAY`)으로 PG 응답을 늦춘다
   - **2a**: PG 지연 6초. 클라이언트 타임아웃(3초)보다 길고 Saga 결제 대기(약 30초)보다 짧다
   - **2b**: PG 지연 45초. Saga 결제 대기보다 길다
   - 결제가 `UNKNOWN`이 되면 바로 `APPROVE`로 되돌린다. 이미 PG에서 처리 중인 요청은 지연 그대로 진행되고, 다른 주문에는 영향이 없다

CI `e2e`가 두 relay 모드에서 시나리오 4 다음에 이 스크립트를 돈다.

## 기대 결과

| | PG | payment-service | order-service (Saga) | 결과 |
|---|---|---|---|---|
| 2a | 6초 뒤 승인 | 3초에 타임아웃 → `UNKNOWN`, **응답 안 함** → 조회로 승인 확인 → `APPROVED` + `PaymentApproved` | `PAYING`에서 기다림 → `COMPLETED` | 정상 완료. 결제·포인트·재고 한 번 |
| 2b | 45초 뒤 승인 | `UNKNOWN` → `CancelPayment` 받음(취소 요청 기록, `PaymentCancelled`) → 45초에 조회로 승인 확인 → **승인 응답 없이 PG 환불** → `REFUNDED` | `Pay` 재전송 2번 → 포기 → `CANCELLING_PAYMENT` → 포인트 환불 → 재고 해제 → `CANCELLED` | 주문 취소, 포인트·재고 원복, PG 원장 승인 + 환불 |

2b에서 바로 보상만 했다면 주문은 취소됐는데 PG에는 승인 거래가 남는다. 결제 서비스가 취소 요청을 기억하고 있다가 늦은 승인을 환불하므로 돈이 남지 않는다.

## 실제 결과

로컬(Windows, Docker Desktop, Debezium relay 모드) 2026-11-23:

```
== 2a. PG 응답이 클라이언트 타임아웃보다 늦음 → UNKNOWN → PG 조회로 승인 확정 → COMPLETED
  ok  PG 타임아웃 → 결제 UNKNOWN (실패로 응답하지 않음)
  ok  결과 모르는 동안 주문 상태 = PENDING
  ok  결과 모르는 동안 Saga 단계 = PAYING
  ok  PG 조회로 승인 확정 → 주문 …7d37e636 COMPLETED
  ok  결제 상태 = APPROVED
  ok  PG 원장 (approved,refunded) = true,false
  ok  포인트 잔액 = 9400
  ok  상품 1 available = 99
== 2b. PG 가 Saga 대기 한도보다 늦게 승인 → 결제 취소·보상 → 늦은 승인 자동 환불
  ok  PG 타임아웃 → 결제 UNKNOWN, PG 는 45000ms 뒤 승인 예정
  ok  결제 포기 → CancelPayment → 포인트 환불 → 재고 해제 → 주문 …39b9ee1d CANCELLED
  ok  포인트 잔액 (환불) = 9400
  ok  상품 1 available (해제) = 99
  ok  취소 직후 결제 상태 (PG 결과 아직 모름) = UNKNOWN
  ok  늦은 승인 확인 → PG 환불 → 결제 REFUNDED
  ok  PG 원장 (approved,refunded) = true,true
  ok  주문 상태 (늦은 승인이 되돌리지 않음) = CANCELLED
  Saga 기록:
    STARTED -> RESERVING_STOCK
    STOCK_RESERVED -> USING_POINT
    POINT_USED -> PAYING
    TIMEOUT_RESEND -> PAYING
    TIMEOUT_RESEND -> PAYING
    TIMEOUT_GIVE_UP -> CANCELLING_PAYMENT
    PAYMENT_CANCELLED -> REFUNDING_POINT
    POINT_REFUNDED -> RELEASING_STOCK
    STOCK_RELEASED -> CANCELLED
PASS
```

2b에서 주문이 취소된 시점(약 31초)에 결제는 아직 `UNKNOWN`이다. 주문 취소와 실제 환불 사이의 이 시간 차는 의도한 것이다. Saga는 결제 서비스가 취소를 접수했다는 응답만 받으면 다음 보상으로 가고, 환불은 결제 서비스가 끝까지 책임진다.

## 단위 테스트로 본 경우

스크립트로 재현하기 번거로운 경우는 `PaymentTimeoutTest`(payment-service)와 `OrderSagaTimeoutTest`(order-service)가 확인한다.

- 결과를 모르는 동안 `Pay`가 다시 와도 PG를 다시 부르지 않고 응답하지 않는다
- PG 조회 결과가 거절이면 실패 응답. 거래가 `not-found-after` 동안 없으면 거절로 확정한다
- 결제 기록이 없는데 취소가 오면 `CANCELLED`를 남기고, 늦게 온 `Pay`는 PG를 부르지 않고 실패로 응답한다
- 이미 승인된 결제를 취소하면 환불한다. 취소가 여러 번 와도 환불은 한 번이다
- `CANCELLING_PAYMENT`에서 늦게 온 `PaymentApproved`·`PaymentFailed`는 무시한다. 취소 커맨드는 응답이 올 때까지 계속 다시 보낸다
