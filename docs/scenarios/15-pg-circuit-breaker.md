# 시나리오 15. PG 장애가 이어지면 → 회로 열림 → 결제 즉시 실패·보상 → 복구 후 닫힘

PG 가 느려지거나 멈추면 지금까지는 결제마다 클라이언트 타임아웃(3초)을 기다린 뒤 `UNKNOWN` 으로 남고, 보정(ADR 0007)이 조회로 확정할 때까지 주문이 결제 대기에 머물렀다. 정합성은 맞지만 장애 동안 빨리 실패하지 못한다. payment-service 의 PG 승인 호출에 서킷 브레이커를 둬서, 실패가 이어지면 PG 를 부르지 않고 곧바로 결제 실패로 응답한다 (PROGRESS 4-4절 ③).

## 설계

| 항목 | 결정 |
|---|---|
| 범위 | PG **승인**만. 조회(`inquire`)·환불(`refund`)은 회로 밖: 보정이 UNKNOWN 을 확정하고 늦은 승인을 환불해야 돈이 맞으므로 회로와 무관하게 자기 주기로 계속 시도한다 |
| 실패로 세는 것 | 타임아웃·오류. PG 의 거절(DECLINE)은 정상 응답이라 세지 않는다 |
| 열기 | 최근 20건(카운트 창) 중 10건 이상 쌓였고 실패 50% 이상 |
| 열린 동안 | PG 를 부르지 않는다 → 요청이 PG 에 가지 않았으므로 **확정 실패**: `DECLINED`("PG unavailable (circuit open)") 기록 + `PaymentFailed` 응답 → Saga 가 포인트 환불 → 재고 해제 → 취소 |
| 이전 시도가 있던 주문 | PG 를 부르기 전 `pg_approve_attempts` 에 시도를 커밋한다. 회로가 열렸을 때 그 주문에 이전 시도가 있으면(타임아웃 뒤 UNKNOWN 기록이 실패해 재전달, Saga 재전송) 이전 요청이 PG 에서 승인됐을 수 있으므로 거절로 확정하지 않고 `UNKNOWN` → 보정이 조회로 확정한다(늦은 승인은 기존처럼 환불) |
| 반열림 | 30초 뒤(`PAYMENT_CB_OPEN_WAIT`) 다음 결제부터 3건을 시험으로 PG 에 보낸다. 시험 호출이 타임아웃이면 지금처럼 UNKNOWN + 보정(ADR 0007), 실패가 많으면 다시 열림 |
| 상태 | 인스턴스마다 따로(공유 저장소 없음). 지표 `payment_pg_circuit_state`(0 닫힘, 1 열림, 2 반열림) |
| 라이브러리 | Resilience4j 코어(`resilience4j-circuitbreaker` 2.3.0). Spring Cloud CircuitBreaker 의 기본 구현이고 상태 머신이 검증돼 있다. Spring 통합 없이 코어만 쓰고 설정은 `payment.circuit-breaker.*` |

## 재현

1. 인프라, 서비스 전부. payment-service 는 `PAYMENT_CB_OPEN_WAIT=5s` 로 띄운다
2. `scripts/smoke-pg-circuit.sh` (사용자 4, 상품 2)

CI `e2e` 가 두 relay 모드에서 마지막 시나리오로 돈다(회로를 여는 동안 UNKNOWN 결제가 생기므로).

## 기대 결과

```
== 15a. PG 지연(5초 > 타임아웃 3초) 동안 주문 10 건 → 회로 열림
  ok  payment_pg_circuit_state = 1 (열림)
== 15b. 열린 동안의 주문은 PG 를 부르지 않고 바로 실패 → 보상 → CANCELLED
  ok  …xxxxxxxx CANCELLED 1초, 사유: PG unavailable (circuit open)
== 15c. PG 복구 → 5초 뒤 시험 호출 성공 → 닫힘, 주문 COMPLETED
  ok  시험 호출 3건 COMPLETED, payment_pg_circuit_state = 0 (닫힘)
PASS
```

## 테스트

- `CircuitBreakingPaymentGatewayTest`: 타임아웃이 쌓이면 열림 + 열린 동안 PG 미호출, 거절은 실패로 세지 않음, 반열림 시험 성공 → 닫힘, 조회·환불은 회로 밖, 끄면 그대로 통과
- `PaymentServiceTest`: 회로 열림 → PG 미호출, DECLINED 기록, 결제 실패 응답
