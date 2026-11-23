# ADR 0013. 관측성: Saga 한 건을 trace 하나로(outbox 를 거쳐도 trace 문맥을 잇는다), 정합성이 깨지기 전에 경보

- 상태: 채택 (트레이싱: PR #27, 메트릭·경보: 후속 1 O-2)
- 날짜: 2026-11-23

## 배경

주문 한 건은 order-service에서 시작해 재고·포인트·결제를 거쳐 알림까지 서비스 다섯 개를 지나간다. 지금까지는 "이 주문이 어디서 멈췄나"를 Saga 기록(`GET /orders/{id}/saga`)과 서비스별 로그를 orderId로 맞춰 보며 답했다. Saga 기록은 order-service가 본 것(커맨드를 보냈고 응답이 왔다)만 있고, 참여 서비스 안에서 걸린 시간이나 메시지가 Kafka에 머문 시간은 보이지 않는다.

분산 트레이싱은 요청 하나에 trace id를 붙여 서비스를 넘어 따라간다. HTTP 호출은 Spring Boot가 헤더(`traceparent`)로 이어 주지만, 이 저장소의 서비스 간 통신은 전부 **outbox를 거친 Kafka 메시지**라 그대로는 끊긴다.

- 비즈니스 트랜잭션 안에서는 메시지를 outbox 테이블에 넣기만 한다
- 실제 발행은 나중에 relay가 한다. 스케줄러 relay는 다른 스레드의 주기 작업이고, Debezium relay는 아예 다른 프로세스(Kafka Connect)다
- 그래서 발행 시점의 trace 문맥은 원래 요청과 관계가 없다. KafkaTemplate의 observation을 켜면 relay의 문맥(대개 없음)으로 새 trace가 시작된다

## 결정

### trace 문맥을 outbox 행에 저장하고, relay는 헤더로 옮기기만 한다

```
HTTP 요청 (traceparent)                      ┌─ inventory-service ─────────────────────┐
  └ order-service: 주문 저장 + outbox INSERT  │ 리스너 observation 이 헤더를 이어받아    │
      (traceparent 컬럼 = 현재 span)          │ 처리 span 을 만들고, 그 안에서 넣는      │
         │                                    │ 응답 outbox 에도 현재 span 을 저장       │
         ▼ relay (스케줄러 / Debezium)        └─────────────────────────────────────────┘
      Kafka 헤더 traceparent  ──────────────────▶        … order-service 리스너 → 다음 커맨드 …
```

- `outbox.traceparent` 컬럼(W3C `traceparent`, null 허용)을 둔다. `OutboxWriter`가 넣는 시점의 현재 span을 저장한다(`OutboxTraceContext`, Micrometer Tracing이 있을 때만)
- 스케줄러 relay는 이 값을 헤더 `traceparent`로 싣는다. Debezium은 Outbox Event Router의 `table.fields.additional.placement`에 `traceparent:header:traceparent`를 더해 같은 헤더로 싣는다. **relay 두 모드가 같은 방식**이고 relay 자체는 span을 만들지 않는다
- 받는 쪽은 Spring Kafka 리스너 observation(`spring.kafka.listener.observation-enabled=true`)이 헤더를 이어받아 처리 span을 만든다. 처리 중에 넣는 다음 outbox 행은 이 span을 부모로 저장하므로 Saga 전체가 이어진다
- **보내는 쪽(KafkaTemplate) observation은 켜지 않는다.** 켜면 relay가 보낼 때 relay 문맥으로 `traceparent`를 덮어써서 끊긴다

결과: 주문 요청 하나 = trace 하나. 정상 주문은 HTTP 1 + 커맨드·응답 6 + `order.events` 2(재고 판매 확정, 알림) = span 9개, 결제 실패는 보상 커맨드·응답까지 같은 trace에 달린다 (시나리오 11).

### 주문 id로 trace를 찾는다

리스너 span에 메시지 키(= orderId, ADR 0005)와 타입(헤더 `type`)을 단다(`MessageTracingConvention`, high cardinality라 span에만 붙고 메트릭 태그에는 안 들어간다). Tempo에서 `{ span.messaging.kafka.message.key = "<orderId>" }`로 그 주문의 trace를 찾는다. trace id를 몰라도 고객 문의(주문 번호)에서 바로 출발할 수 있다.

### 수집·조회: OpenTelemetry(OTLP) → Tempo, Grafana로 본다

- 서비스: `spring-boot-starter-opentelemetry`(Micrometer Tracing + OTel 브리지 + OTLP exporter). 표준(W3C trace context, OTLP)만 쓰므로 수집 백엔드는 바꿀 수 있다
- 로컬·CI: Grafana Tempo 2.10 단일 바이너리 + Grafana (`infra/docker-compose.yml`). Tempo를 고른 이유는 다음 PR의 메트릭(Prometheus)과 같은 Grafana에서 trace ↔ 메트릭을 오가기 위해서다. Jaeger도 OTLP를 받으므로 비교 대상이었지만 UI가 따로 생긴다
- 샘플링: 로컬·CI는 전부(1.0). 운영이라면 `TRACING_SAMPLING_PROBABILITY`로 낮추거나 tail sampling(Collector)으로 오류·느린 trace만 남긴다
- OTLP 메트릭 export는 끈다(starter가 가져오는 레지스트리). 메트릭은 Prometheus가 긁어 간다(아래)
- 호스트 포트는 Tempo API 3210, OTLP 4328. 같은 PC에서 다른 관측성 스택이 표준 포트(3200, 4318)를 쓰고 있어도 겹치지 않게 비켜 뒀다

### 메트릭·경보: 정합성이 깨지기 전 신호를 본다 (후속 1 O-2)

trace는 "이 주문이 어떻게 지나갔나"를 답하지만, "지금 어딘가 막혀 있나"는 메트릭과 경보가 답한다. 3단계에서 "경보는 후속 관측성 작업"으로 남긴 것들(결제 `UNKNOWN`, 환불 대기, 보상 무한 재전송, slot 적체, lag)을 여기서 경보로 만든다.

**어디서 재나.** 서비스는 자기 DB만 본다는 경계를 그대로 지킨다.

| 메트릭 | 출처 | 의미 |
|---|---|---|
| `commerce_saga_active{state,phase}`, `commerce_saga_oldest_active_age_seconds{state,phase}` | order-service (`SagaMetrics`, Saga 테이블 집계) | 끝나지 않은 단계별 Saga 수, 가장 오래된 것이 시작한 지 얼마나 됐나 |
| `commerce_saga_timeout_resends_total`, `commerce_saga_timeout_give_ups_total` | order-service (Saga 기록 집계) | 단계 타임아웃 재전송·포기 누적 |
| `commerce_payment_unknown`, `commerce_payment_oldest_unknown_age_seconds`, `commerce_payment_refund_pending`, `commerce_payment_oldest_refund_pending_age_seconds` | payment-service (`PaymentMetrics`) | PG 결과를 모르는 결제, 취소 요청 + 승인이라 환불을 기다리는 결제 |
| `commerce_outbox_pending_rows`, `commerce_outbox_oldest_pending_age_seconds`, `commerce_outbox_replication_slot_lag_bytes` | outbox를 쓰는 네 서비스 (`OutboxMetrics`, libs/outbox 자동 설정) | 스케줄러 relay 적체, 자기 DB slot이 붙잡은 WAL |
| `kafka_consumergroup_lag`, `kafka_topic_partition_current_offset{topic=~".+[.]DLT"}` | kafka-exporter (브로커 기준) | 컨슈머 그룹 lag, DLT에 쌓인 메시지. 서비스가 죽어 있어도 보인다 |

- 서비스 메트릭은 `/actuator/prometheus`(`micrometer-registry-prometheus`). 값은 스크레이프 때 집계 쿼리로 읽고 2초 동안 재사용한다(게이지마다 쿼리하지 않게). 쿼리는 진행 중 Saga·보정 대상 부분 인덱스와 같은 조건이라 끝난 행이 쌓여도 비싸지지 않는다. 타임아웃 이벤트 수는 Saga 기록 전체를 세므로 기록이 아주 많아지면 카운터로 바꾼다
- 메트릭 이름 앞에 `commerce_`를 붙여 JVM·HTTP·Kafka 클라이언트 기본 메트릭과 구분한다. 주문 id 같은 high cardinality 값은 태그로 넣지 않는다(trace에만)
- 집계 쿼리를 애플리케이션이 Micrometer를 모르게 두었다: `adapter.in.metrics`(MeterBinder) → `port.in`(`GetSagaStatsUseCase`) → `port.out` → 영속성 어댑터. 헥사고날 규칙(ArchUnit) 그대로

**경보 규칙** (`infra/prometheus/rules/commerce.yml`). 문턱값은 로컬·CI 시나리오(단계 타임아웃 10초)에서 바로 보이도록 짧게 잡았다. 운영 값은 오른쪽 열처럼 늘린다.

| 경보 | 조건 (이 저장소) | 운영이라면 | 무엇을 의심하나 |
|---|---|---|---|
| `ServiceDown` | `up == 0` 30초 | 1~2분 | 서비스 프로세스 다운 (시나리오 4) |
| `SagaStuck` | 정방향 Saga가 시작 후 60초 넘게 안 끝남 | 단계 타임아웃 × (재전송+1) 보다 조금 길게 (기본 2~3분) | 참여 서비스·relay 장애 |
| `SagaCompensationStuck` | 보상 단계가 120초 넘게 안 끝남 | 5~10분 | 보상 커맨드가 계속 실패 (재고·포인트가 묶임) |
| `SagaTimeoutGiveUp` | 최근 5분 포기 횟수 증가 | 같음 | 응답이 없어 주문이 취소됨 |
| `PaymentResultUnknown` | `UNKNOWN`이 기록 후 30초 넘음 | 5분 | PG 장애·지연 (시나리오 2) |
| `PaymentRefundPending` | 환불 대기가 60초 넘음 | 10분 | 돈은 나갔는데 주문은 취소된 상태 |
| `DeadLetterMessage` | `*.DLT` 끝 오프셋이 5분 안에 증가 | 같음 | 처리 불가 메시지 (시나리오 6) |
| `ConsumerLagHigh` | 그룹·토픽 lag > 20 이 30초 | 처리량에 맞춰 | 컨슈머 다운, 분류 안 된 영구 오류로 파티션 막힘 |
| `OutboxBacklog` | 미발행 outbox가 30초 넘음 | 1~2분 | 스케줄러 relay가 Kafka에 못 보냄 |
| `ReplicationSlotLag` | slot이 WAL 100 MB 넘게 붙잡음 1분 | 디스크 크기에 맞춰 (예: 여유 공간의 10%) | Debezium 커넥터 중단·막힘 (시나리오 5, ADR 0004 위험 2) |

- 수집: Prometheus 3.15(compose, 호스트 포트 9091, 다른 Prometheus와 겹치지 않게). 서비스는 호스트에서 뜨므로 `host.docker.internal`(리눅스 CI는 `host-gateway`)로 긁는다
- lag·DLT는 서비스 메트릭(Kafka 클라이언트 `records-lag`) 대신 kafka-exporter를 쓴다. 서비스가 죽으면 서비스 메트릭도 같이 사라지는데, 그때가 lag를 봐야 할 때라서다. kafka-exporter 1.11은 Kafka Connect 그룹의 할당 정보를 해석하지 못해 오류 로그를 남기지만, 서비스 컨슈머 그룹 lag는 정상으로 나온다
- 경보 전달(Alertmanager → 메일·메신저)은 두지 않았다. Prometheus·Grafana에서 firing을 확인하는 데까지가 범위다. 운영이면 Alertmanager로 심각도별 라우팅을 둔다
- Grafana 대시보드 `commerce-saga`(프로비저닝): firing 경보 표, 단계별 진행 중 Saga·가장 오래된 나이, 재전송·포기, 결제 `UNKNOWN`·환불 대기, 그룹별 lag, DLT, outbox·slot 적체
- 시나리오 12가 장애를 넣고 경보가 실제로 firing 되는지 CI에서 확인한다

## 결과

- 좋은 점: Saga 한 건이 서비스·relay 모드와 상관없이 trace 하나로 보인다. 어느 서비스에서 얼마나 걸렸는지, 메시지가 Kafka·relay에서 얼마나 머물렀는지(앞 span 끝 ~ 다음 span 시작)가 보인다. 메커니즘이 outbox 컬럼 하나와 헤더 하나라 단순하다
- 나쁜 점: outbox 스키마에 컬럼이 하나 는다(서비스마다 Flyway). Debezium 커넥터 설정이 그 컬럼을 참조하므로 **서비스(마이그레이션)를 먼저 배포하고 커넥터 설정을 나중에** 바꾼다. 반대 순서면 Event Router가 없는 컬럼 때문에 실패한다(`table.field.additional.missing=error`)
- 한계
  - 문맥 없이 시작하는 작업은 새 trace가 된다: 단계 타임아웃 재전송(`SagaTimeoutService`), 결제 보정 후 응답(`PaymentReconcileService`), traceparent가 없던 예전 메시지. 이때도 리스너 span에 orderId가 붙어 있어 주문 id 검색으로 같은 주문의 trace들을 모아 볼 수 있다. 재전송까지 원래 trace에 잇고 싶다면 Saga 행에 처음 traceparent를 저장해 두는 방법이 있다(필요해지면)
  - DB 쿼리(JDBC) span은 없다. 서비스 안에서 느린 곳은 지금은 처리 span 길이로만 본다
  - Tempo는 로컬 디스크·24시간 보존이다. 운영이면 오브젝트 스토리지와 보존 기간을 정한다
  - 경보 문턱값은 시연용으로 짧다(위 표). Prometheus 규칙 파일은 환경 변수를 받지 않으므로 운영에서는 규칙 파일을 따로 둔다
  - 메트릭 게이지는 스크레이프마다 DB 집계 쿼리를 한 번 돈다(2초 캐시). 인스턴스가 여럿이면 같은 값이 인스턴스마다 나오므로 경보는 `max`로 묶는다
