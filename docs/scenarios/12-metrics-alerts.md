# 시나리오 12. 메트릭·경보: 장애를 넣으면 경보가 실제로 울린다

분산 정합성이 깨지기 직전의 신호(처리 못 한 메시지, 결과를 모르는 결제, 재전송을 포기한 Saga)가 메트릭으로 나오고 경보 규칙이 실제로 firing 되는지 본다. 경보가 이미 다른 이유로 firing 중일 수 있으므로 **이번 장애로 메트릭 값이 늘었는지**도 따로 확인한다. 설계는 [ADR 0013](../adr/0013-observability.md)의 메트릭·경보 부분, 규칙은 `infra/prometheus/rules/commerce.yml`.

## 재현

1. 인프라(Prometheus, kafka-exporter 포함)와 다섯 서비스를 띄운다. 타임아웃은 CI `e2e`와 같은 값
   - order-service: `SAGA_STEP_TIMEOUT=10s SAGA_PAYMENT_MAX_RESENDS=2 SAGA_TIMEOUT_SCAN_INTERVAL=1s`
   - payment-service: `PG_CLIENT_TIMEOUT=3s`(기본) `PAYMENT_RECONCILE_INTERVAL=1s`
2. `scripts/smoke-alerts.sh`
   - **12a**: `payment.command`에 깨진 JSON(`type: Pay`)을 넣는다 → payment-service가 `payment.command.DLT`로 옮김 → kafka-exporter의 DLT 끝 오프셋 증가 → `DeadLetterMessage{topic="payment.command.DLT"}`
   - **12b**: PG를 45초 늦춘다(시나리오 2b와 같은 상황) → 결제 `UNKNOWN` → Saga가 결제를 약 30초 기다리다 포기(`commerce_saga_timeout_give_ups_total` 증가) → `SagaTimeoutGiveUp` → `UNKNOWN`이 30초를 넘어 `PaymentResultUnknown` → PG가 늦게 승인 → 결제 서비스가 환불(`REFUNDED`) → `PaymentResultUnknown` 해제
3. 눈으로 보려면 Grafana(http://localhost:3000) 대시보드 `commerce-saga`, 또는 Prometheus(http://localhost:9091/alerts)

CI `e2e`가 두 relay 모드에서 시나리오 11 다음에 이 스크립트를 돈다.

## 기대 결과

| | 메트릭 | 경보 |
|---|---|---|
| 12a | `kafka_topic_partition_current_offset{topic="payment.command.DLT"}` +1 | `DeadLetterMessage` firing (5분 동안) |
| 12b | `commerce_payment_unknown` 1 → 0, `commerce_payment_oldest_unknown_age_seconds` 30 초과, `commerce_saga_timeout_give_ups_total` +1 | `SagaTimeoutGiveUp` firing, `PaymentResultUnknown` firing → 환불 후 해제 |

## 실제 결과

로컬(Windows, Docker Desktop, Debezium relay 모드) 2026-11-23:

```
== 12a. 깨진 메시지 → payment.command.DLT → DeadLetterMessage firing
  ok  payment.command.DLT 메시지 2 → 3
  ok  DeadLetterMessage{topic=payment.command.DLT} firing
== 12b. PG 지연 45초 → PaymentResultUnknown, SagaTimeoutGiveUp firing → 환불 후 UNKNOWN 경보 해제
  주문 …4ee48955
  ok  commerce_saga_timeout_give_ups_total 5 → 6, SagaTimeoutGiveUp firing (결제 응답을 기다리다 포기 → 결제 취소·보상)
  ok  PaymentResultUnknown firing (UNKNOWN 30초 넘음)
  ok  늦은 승인 → 환불(REFUNDED) → PaymentResultUnknown 해제
  경보: DeadLetterMessage(firing) SagaTimeoutGiveUp(firing)
PASS
```

로컬은 같은 스크립트를 여러 번 돌려 DLT 메시지·포기 횟수가 0부터 시작하지 않았다. 그래서 "경보가 firing 중"만이 아니라 값이 늘었는지를 본다.

CI 결과는 이 PR의 `e2e` 실행 로그로 채운다.

## 만들면서 발견한 것

- 처음에는 `UNKNOWN` 나이를 `updated_at`부터 쟀다. 그러면 Saga가 결제를 포기하고 `CancelPayment`를 보내는 순간(약 30초) 결제 행의 `updated_at`이 바뀌어 나이가 0으로 돌아가고, PG가 45초에 승인할 때까지 `UNKNOWN`이 45초 남아 있어도 경보가 울리지 않았다. 나이는 결제가 `UNKNOWN`으로 기록된 시각(`created_at`, PG 타임아웃 시점)부터 잰다. 환불 대기 나이는 취소 요청을 받은 시각(`updated_at`)부터가 맞다 (`PaymentStatsPersistenceAdapterTest`)
- kafka-exporter 1.11은 Kafka Connect 그룹(`commerce-connect`)의 할당 정보를 해석하지 못해 매 수집마다 오류 로그를 남긴다. 서비스 컨슈머 그룹 다섯 개의 lag는 정상으로 나온다

## 관련 테스트

- `SagaStatsPersistenceAdapterTest`(order), `PaymentStatsPersistenceAdapterTest`(payment): 집계 쿼리 (Testcontainers)
- `OutboxWriterIntegrationTest`: outbox 미발행 행, 아무도 읽지 않는 slot이 WAL을 붙잡는 것 (`OutboxMetrics`)
- 규칙 문법: `promtool check rules infra/prometheus/rules/commerce.yml`
