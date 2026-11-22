# 시나리오 8. 알림 서비스 다운 → 주문은 정상 완료 (장애 격리)

후속 처리(코레오그래피) 소비자가 죽어 있어도 주문 Saga는 영향 없이 끝나고, 소비자가 다시 뜨면 밀린 이벤트를 이어서 처리하는지 확인한다.

## 재현

1. 인프라와 inventory·point·payment·order 네 서비스만 띄운다. **notification-service는 띄우지 않는다.**
2. `scripts/smoke-saga.sh`: 정상 주문 `COMPLETED`, 품절·결제 실패 주문 `CANCELLED` (그동안 `order.events`에 이벤트가 쌓인다)
3. notification-service(8085)를 띄운다
4. `scripts/smoke-notification-catchup.sh`: 사용자 1·2의 알림 목록에 2번 주문들의 완료·취소 알림이 생겼는지 확인

CI `e2e`가 이 순서 그대로 돈다.

## 기대 결과

| 단계 | 기대 |
|---|---|
| 2 | 알림 서비스 없이도 주문이 `COMPLETED` / `CANCELLED`로 끝난다 (order-service는 알림 서비스를 모른다) |
| 4 | 알림 서비스가 뜨면 컨슈머 그룹 오프셋부터 밀린 이벤트를 처리해 알림이 생긴다 |
| 중복 | 같은 이벤트가 다시 와도 알림은 한 번 (`notifications (order_id, type)` 유니크) |

## 실제 결과

RESULT_PLACEHOLDER

## 왜 이렇게 되나

- order-service는 `order.events`에 사실을 남길 뿐 누가 받는지 모른다 (ADR 0003). 동기 호출이었다면 알림 장애가 주문 실패로 번졌다.
- Kafka가 이벤트를 보관하고 컨슈머 그룹 오프셋이 "어디까지 처리했나"를 기억한다. 소비자가 다시 뜨면 거기서부터 읽는다.
- 알림은 at-least-once로 오므로 (order_id, type) 유니크로 한 번만 만든다.
