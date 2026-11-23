# 시나리오 11. 분산 트레이싱: Saga 한 건이 서비스 다섯 개를 지나도 trace 하나

주문 요청 하나가 order → inventory → point → payment → (order.events) inventory·notification을 거치는 동안 **trace 하나**로 이어지는지 본다. 서비스 간 통신이 전부 outbox를 거친 Kafka 메시지라 HTTP처럼 저절로 이어지지 않는다. trace 문맥을 outbox 행에 저장하고 relay가 헤더로 옮기는 방식이 relay 두 모드(스케줄러, Debezium)에서 모두 동작하는지 확인한다. 설계는 [ADR 0013](../adr/0013-observability.md).

## 재현

1. 인프라(Tempo·Grafana 포함)와 다섯 서비스를 띄운다. relay 모드는 상관없다
2. `scripts/smoke-tracing.sh`
   - 요청에 `traceparent`를 직접 넣어 trace id를 알고 시작한다
   - **11a**: 정상 주문 → `COMPLETED` → Tempo(`GET /api/traces/<id>`)에 span이 다 모일 때까지 기다린 뒤 서비스 다섯 개가 다 있는지, 주문 id로 검색(`{ span.messaging.kafka.message.key = "<orderId>" }`)해서 같은 trace 하나가 나오는지
   - **11b**: 결제 실패 주입(`DECLINE`) → `CANCELLED` → 보상 커맨드·응답까지 같은 trace에 있는지
3. 눈으로 보려면 Grafana(http://localhost:3000) → Explore → Tempo → trace id 또는 TraceQL

CI `e2e`가 두 relay 모드에서 시나리오 2 다음에 이 스크립트를 돈다.

## 기대 결과

| | span (서비스) | 결과 |
|---|---|---|
| 11a 정상 | `http post /orders`(order) → `inventory.command`(inventory) → `inventory.reply`(order) → `point.command`(point) → `point.reply`(order) → `payment.command`(payment) → `payment.reply`(order) → `order.events` × 2(inventory 판매 확정, notification) | 9개, 서비스 5개, 주문 id 검색 = 같은 trace |
| 11b 결제 실패 | 위 정방향 + `point.command`(`RefundPoint`) → `point.reply` → `inventory.command`(`ReleaseStock`) → `inventory.reply` → `order.events` | 보상까지 한 trace |

각 리스너 span에는 `messaging.kafka.message.key`(orderId)와 `messaging.message.type`(`ReserveStock`, `PointUsed` …)이 붙는다.

## 실제 결과

로컬(Windows, Docker Desktop, Debezium relay 모드) 2026-11-23:

```
== 11a. 정상 주문 → trace 하나에 다섯 서비스
  ok  주문 …4461d541 COMPLETED, trace ef9af5c9567bcc05824d225af24395ad
  ok  trace 하나에 다섯 서비스: inventory-service notification-service order-service payment-service point-service
            1 http post /orders
            1 inventory.command process
            1 inventory.reply process
            2 order.events process
            1 payment.command process
            1 payment.reply process
            1 point.command process
            1 point.reply process
  ok  주문 id 로 검색 → 같은 trace 하나
== 11b. 결제 실패 → 보상까지 같은 trace
  ok  주문 …22311e0b CANCELLED, trace cd6d8788bea46399f9b0c26a584ffb59
  ok  trace 하나에 다섯 서비스: inventory-service notification-service order-service payment-service point-service
            1 http post /orders
            2 inventory.command process
            2 inventory.reply process
            2 order.events process
            1 payment.command process
            1 payment.reply process
            2 point.command process
            2 point.reply process
PASS
```

11b의 `inventory.command`·`point.command` 2개는 정방향(`ReserveStock`, `UsePoint`)과 보상(`ReleaseStock`, `RefundPoint`)이다. `order.events` 2개는 `OrderCancelled`를 받은 notification과, 관심 없는 타입이라 건너뛰는 inventory다(span은 받은 메시지마다 생긴다).

CI 결과는 이 PR의 `e2e` 실행 로그로 채운다.

## 확인하면서 본 것

- 주기 작업(단계 타임아웃 스캔 1초, 결제 보정 1초)은 trace를 만들지 않았다(Tempo 최근 10분 검색에 주기 작업 root trace 없음, Debezium 모드). 그래서 trace 목록이 주기 작업으로 덮이지 않는다. 스케줄러 relay는 span 없이 헤더만 옮긴다
- traceparent가 없는 메시지(이 변경 전에 쌓인 이벤트, 단계 타임아웃 재전송)는 받는 쪽에서 새 trace로 시작한다. 알림 서비스가 밀린 예전 이벤트를 처리할 때 그런 trace가 수십 개 생겼다. 이때도 orderId 검색으로는 찾힌다
- Debezium 모드에서 `traceparent`가 null인 행(타임아웃 재전송)도 커넥터 오류 없이 발행됐다
