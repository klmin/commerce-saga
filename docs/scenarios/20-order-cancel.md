# 시나리오 20. 주문 취소: 진행 중이면 다음 응답에서 보상, 완료 후면 결제 환불 → 포인트 환불 → 재고 반환

사용자가 주문을 언제든 취소할 수 있고, 돈·포인트·재고가 정방향의 역순으로 정확히 한 번 되돌아가는지 확인한다. 설계는 [ADR 0020](../adr/0020-order-cancellation.md).

## 설계

| 항목 | 결정 |
|---|---|
| API | `POST /orders/{orderId}/cancel` (Gateway `/api/orders/{id}/cancel`). 본인 주문만, 남의 주문·없는 주문은 `404`(IDOR). 접수되면 `202`, 이미 취소가 끝난 주문이면 `200`. 여러 번 보내도 한 번만 반영 |
| Saga | 주문마다 하나인 기존 Saga 의 단계로 처리한다(별도 취소 Saga 없음). 취소 요청은 Saga(`cancel_requested`, `after_completion`)와 주문(`cancel_requested_at`, 화면 표시용)에 남는다 — order V9 |
| 진행 중 취소 | 요청만 기록한다. 지금 단계의 **정방향 성공 응답**이 오면 다음 단계로 가지 않고 보상으로: 재고 예약됨 → 재고 해제, 포인트 차감됨 → 포인트 환불 → 재고 해제, 결제 승인 → 결제 취소(환불) → 포인트 환불 → 재고 해제. 실패 응답은 원래대로 보상(사유는 그 실패). 결제 중이면 승인됐을 수 있어 응답을 기다린다(ADR 0007) |
| 완료 후 취소 | `COMPLETED` → `CANCELLING_PAYMENT`(기존 `CancelPayment`, 승인된 결제는 결제 서비스가 환불) → `REFUNDING_POINT`(기존 `RefundPoint`) → `RETURNING_STOCK`(새 `ReturnStock`) → `CANCELLED`. 결제 금액이 0 이거나 포인트를 쓰지 않았으면 그 단계는 건너뛴다 |
| 재고 반환 | inventory 가 예약 상태로 판단한다. 판매 확정(`CONFIRMED`)이면 가용으로 다시 들이고, 아직 확정 전(`RESERVED`, `OrderCompleted` 가 늦음)이면 예약을 푼다. 둘 다 `RETURNED` 가 되어 뒤늦은 `OrderCompleted` 는 아무것도 하지 않는다 |
| 주문 상태 | 취소가 끝날 때까지 `PENDING`/`COMPLETED` 그대로, 응답에 `cancelRequestedAt`. 끝나면 `CANCELLED`, 사유 "사용자 취소". 완료된 주문은 취소를 요청했을 때만 `CANCELLED` 로 바뀐다(`Order.cancelAfterCompletion`, 늦은 보상 결과로 완료 주문이 취소되지 않게) |
| 이벤트 | 끝나면 `OrderCancelled` 에 `afterCompletion`(추가한 필드, 옛 메시지는 false) |
| 타임아웃 | 기존 규칙 그대로. 완료 후 취소의 단계도 보상이라 포기하지 않고 계속 다시 보낸다 |

## 재현

1. 인프라와 네 서비스(inventory, point, payment, order)를 띄운다 (시나리오 0 과 같음)
2. `scripts/smoke-order-cancel.sh` (사용자 2, 시드 상품 mouse)

CI `e2e` 가 두 relay 모드(scheduler·debezium)에서 시나리오 10 다음에 돈다.

## 기대 결과

| 단계 | 기대 |
|---|---|
| 20a | 완료된 주문(포인트 300) 취소 `202` → `CANCELLED`, 사유 "사용자 취소". 결제 `REFUNDED`, 포인트·가용 재고·예약 재고가 주문 전과 같다. Saga 단계 `COMPLETED → CANCELLING_PAYMENT → REFUNDING_POINT → RETURNING_STOCK → CANCELLED`. 다시 취소하면 `200`, 포인트·재고 그대로 |
| 20b | PG 지연(2초) 중 `PAYING` 에서 취소 `202`(주문은 아직 `PENDING`) → 승인 응답을 받은 뒤 결제 취소부터 보상 → `CANCELLED`. Saga 기록이 `CANCEL_REQUESTED … PAYMENT_APPROVED … PAYMENT_CANCELLED … POINT_REFUNDED … STOCK_RELEASED` 순서, 결제 `REFUNDED`, 포인트·재고 원복 |
| 20c | 남의 주문 취소 `404`, 주문은 `COMPLETED` 그대로이고 `cancelRequestedAt` 없음 |

테스트로도 확인한다.

- `OrderSagaCancelTest`: 진행 중 각 단계(재고 예약·포인트 차감·결제 승인/실패·보상 중), 완료 후(전체·결제만·포인트만), 중복·끝난 뒤 요청, 반환 단계에 온 엉뚱한 응답 무시, 반환 단계 타임아웃 재전송, 복원
- `CancelOrderServiceTest`: 소유권 404, 진행 중 → 다음 응답에서 보상, 완료 후 커맨드 순서와 `afterCompletion` 이벤트, 중복 요청에 커맨드 한 번, 실패로 취소된 주문
- `OrderTest`: 요청은 한 번만, 요청 없는 완료 주문은 취소되지 않음
- inventory `StockServiceTest`·`ReserveStockIntegrationTest`(8-1): 확정 후·확정 전 반환, 늦은 확정, 중복 반환

## 결과

CI `e2e`(두 relay 모드) 결과를 PR 에 남긴다.
