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
- 샘플링: 로컬·CI는 전부(1.0). 운영이라면 `TRACING_SAMPLING_PROBABILITY`로 낮추거나 tail sampling(Collector)으로 오류·느린 trace만 남긴다 → 수집기를 넣은 뒤 tail sampling 으로 했다(아래 "로그와 수집기")
- OTLP 메트릭 export는 끈다(starter가 가져오는 레지스트리). 메트릭은 `/actuator/prometheus` 를 긁어 간다(아래, 지금은 Alloy 가 긁어 Prometheus 로 보낸다)
- 호스트 포트는 Tempo API 3210, OTLP 4328(아래 로그 절부터는 Alloy 가 받는다). 같은 PC에서 다른 관측성 스택이 표준 포트(3200, 4318)를 쓰고 있어도 겹치지 않게 비켜 뒀다

### 메트릭·경보: 정합성이 깨지기 전 신호를 본다 (후속 1 O-2)

trace는 "이 주문이 어떻게 지나갔나"를 답하지만, "지금 어딘가 막혀 있나"는 메트릭과 경보가 답한다. 3단계에서 "경보는 후속 관측성 작업"으로 남긴 것들(결제 `UNKNOWN`, 환불 대기, 보상 무한 재전송, slot 적체, lag)을 여기서 경보로 만든다.

**어디서 재나.** 서비스는 자기 DB만 본다는 경계를 그대로 지킨다.

| 메트릭 | 출처 | 의미 |
|---|---|---|
| `commerce_saga_active{state,phase}`, `commerce_saga_oldest_active_age_seconds{state,phase}` | order-service (`SagaMetrics`, Saga 테이블 집계) | 끝나지 않은 단계별 Saga 수, 가장 오래된 것이 시작한 지 얼마나 됐나 |
| `commerce_saga_timeout_resends_total`, `commerce_saga_timeout_give_ups_total` | order-service (Saga 기록 집계) | 단계 타임아웃 재전송·포기 누적 |
| `commerce_payment_unknown`, `commerce_payment_oldest_unknown_age_seconds`, `commerce_payment_refund_pending`, `commerce_payment_oldest_refund_pending_age_seconds` | payment-service (`PaymentMetrics`) | PG 결과를 모르는 결제, 취소 요청 + 승인이라 환불을 기다리는 결제 |
| `commerce_outbox_pending_rows`, `commerce_outbox_oldest_pending_age_seconds`, `commerce_outbox_replication_slot_lag_bytes` | outbox를 쓰는 네 서비스 (`OutboxMetrics`, libs/outbox 자동 설정) | 스케줄러 relay 적체, 자기 DB slot이 붙잡은 WAL(`restart_lsn` 기준: 오래 열린 트랜잭션이 있으면 `confirmed_flush_lsn`이 앞서 가도 그 뒤 WAL은 못 지운다) |
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

- 저장·경보: Prometheus 3.15(compose, 호스트 포트 9091, 다른 Prometheus와 겹치지 않게). 수집은 처음엔 Prometheus 가 직접 했고, 지금은 Alloy 가 긁어 보낸다(아래 "메트릭도 Alloy 로"). 서비스는 호스트에서 뜨므로 `host.docker.internal`(리눅스 CI는 `host-gateway`)로 긁는다
- lag·DLT는 서비스 메트릭(Kafka 클라이언트 `records-lag`) 대신 kafka-exporter를 쓴다. 서비스가 죽으면 서비스 메트릭도 같이 사라지는데, 그때가 lag를 봐야 할 때라서다. kafka-exporter 1.11은 Kafka Connect 그룹의 할당 정보를 해석하지 못해 오류 로그를 남기지만, 서비스 컨슈머 그룹 lag는 정상으로 나온다
- 경보 전달(Alertmanager → 메일·메신저)은 두지 않았다. Prometheus·Grafana에서 firing을 확인하는 데까지가 범위다. 운영이면 Alertmanager로 심각도별 라우팅을 둔다
- Grafana 대시보드 `commerce-saga`(프로비저닝): firing 경보 표, 단계별 진행 중 Saga·가장 오래된 나이, 재전송·포기, 결제 `UNKNOWN`·환불 대기, 그룹별 lag, DLT, outbox·slot 적체
- 시나리오 12가 장애를 넣고 경보가 실제로 firing 되는지 CI에서 확인한다

### 로그와 수집기: 서비스 → OTLP → Alloy → Tempo·Loki (2026-11-24 추가)

trace 는 "어디서 얼마나"를 보여 주지만 "왜"(거절 사유, 무시한 응답, 보상 판단)는 로그에 있다. 그런데 로그는 서비스마다 콘솔·파일에 흩어져 있어, trace 에서 본 주문 한 건의 로그를 보려면 서비스 일곱 개의 로그를 시간으로 맞춰 찾아야 했다.

- **로그 저장소: Loki 3.7** (단일 바이너리, 로컬 디스크, 호스트 포트 3110). Grafana 에 같이 붙어 trace ↔ 로그를 링크로 오간다. Elasticsearch 는 후속 4(검색)에 쓰지만 로그용으로는 무겁고 Grafana 링크가 덜 자연스럽다
- **수집기: Grafana Alloy 1.21** (OpenTelemetry Collector 배포판). 서비스는 trace·로그를 OTLP 로 Alloy 하나에 보내고, Alloy 가 trace 는 Tempo, 로그는 Loki 로 나눈다(`infra/alloy/config.alloy`). 서비스는 수집기 주소만 알아서 저장소를 바꾸거나 tail sampling·필터를 넣어도 서비스 설정은 그대로다. 호스트 OTLP 포트 4328 은 Tempo 대신 Alloy 로 옮겼고(서비스 설정 기본값 그대로), Alloy UI 는 12346
- **로그 보내는 법: OTLP (파일·stdout 수집이 아니라).** 로컬·CI 는 서비스가 호스트에서 `java -jar`로 돌아 컨테이너 로그가 아니다. Spring Boot 4.2 는 OTLP 로그 exporter(`management.opentelemetry.logging.export.otlp.endpoint`)까지만 만들고 Logback appender 는 붙이지 않아서, 서비스마다 `OpenTelemetryLoggingConfig` 가 기동 후 root logger 에 OpenTelemetry appender 를 붙인다(`opentelemetry-logback-appender-1.0`). 콘솔 로그는 그대로다. span 안에서 찍은 로그는 `trace_id`·`span_id` 가 함께 가고, Loki 는 이것을 structured metadata 로, `service.name` 을 라벨 `service_name` 으로 둔다
- **Grafana 링크**: Tempo span → "Related logs" 는 `{service_name="<서비스>"} | trace_id="<trace>"`, Loki 로그의 `trace_id` → Tempo trace (`infra/grafana/provisioning/datasources`)
- ~~메트릭은 Alloy 를 거치지 않는다.~~ → 2026-11-25 에 바꿨다(아래 "메트릭도 Alloy 로")
- order-service 는 Saga 전이마다 `Saga 진행: <응답> -> <상태>` 를 남긴다. 주문 한 건의 흐름을 trace 와 같은 trace_id 의 로그로 함께 본다
- 시나리오 11c 가 결제 실패 주문의 trace id 로 Loki 에서 order·notification 두 서비스의 로그가 찾히는지 CI 에서 확인한다
- **샘플링은 Alloy 에서 (tail sampling, 2026-11-24 추가)**. 서비스는 전부 보내고(`TRACING_SAMPLING_PROBABILITY=1.0`), Alloy 가 trace 를 10초 모았다가 고른다. 오류(status ERROR, 그리고 `@ExceptionHandler` 가 처리해 ERROR 가 아닌 5xx 는 `outcome=SERVER_ERROR`)·1초 넘는 trace 는 늘 남기고(4xx 는 서버가 정상으로 거절한 것이라 남기지 않고 건수는 메트릭으로 본다), actuator(헬스 체크·Prometheus 수집) trace 는 늘 버리며, 나머지는 `TRACE_SAMPLING_PERCENT`(기본 100, 차트 `observability.traceSamplingPercent`)만큼 남긴다. 서비스에서 비율을 낮추는 head sampling 은 오류가 날 요청인지 모르고 버리지만, tail sampling 은 끝난 trace 를 보고 정해 오류·지연을 놓치지 않는다. 비율은 trace id 로 정하고, 남긴 trace 의 늦은 span 은 decision cache 로 같은 결정을 따른다. 기다리는 시간은 10초다. 비율이 100% 면 결과가 같기 때문이고, 비율을 낮추면 30초로 올린다(오래 걸린 Saga 의 늦은 오류 span 을 놓치지 않게). 대가는 Alloy 메모리와 Tempo 에 보이기까지의 지연, 그리고 Alloy 를 여러 개 띄우면 trace id 로 나눠 주는 앞단(loadbalancing exporter)이 필요하다는 것. 로그는 샘플링하지 않는다
- k8s(Helm)도 같은 구성을 클러스터 안에 둔다(`templates/observability.yaml`, 설정 파일은 `files/observability` 사본). 끄면(`observability.enabled=false`) 서비스는 trace 샘플링 0·로그 export 끔 (ADR 0014 "관측성")

### 메트릭도 Alloy 로: 수집기 하나, Prometheus 는 저장·경보 (2026-11-25 변경)

처음엔 "메트릭은 Alloy 를 거치지 않는다(Prometheus 가 직접 긁는다)"로 했다. 그런데 로컬 클러스터에서 tempo(검색 중 메모리 512Mi 초과)와 Kafka Connect(Strimzi 기본 힙 75% + 힙 밖 > 1Gi)가 몇 시간 동안 `OOMKilled` 를 반복했는데 아무 경보도 없었다. 관측성 스택 자신과 컨테이너 메모리는 수집 대상이 아니었기 때문이다. 수집 대상을 늘리면서 수집을 한 곳으로 모은다.

```
trace  : 서비스 ──OTLP push──▶ Alloy ──▶ Tempo
로그   : 서비스 ──OTLP push──▶ Alloy ──▶ Loki
메트릭 : Alloy ──scrape(pull)──▶ 서비스 7개 · kafka-exporter · tempo · loki · prometheus · alloy 자신
                               · (k8s 만) 노드 kubelet 의 컨테이너 지표(cAdvisor)
               ──remote_write──▶ Prometheus (--web.enable-remote-write-receiver, 저장·경보 규칙)
```

- **수집기는 Alloy 하나.** 수집 대상·라벨·필터를 한 파일(`infra/alloy/config.alloy`)에서 본다. 저장소를 바꿔도(예: Mimir) remote_write 주소만 바꾼다. 다른 프로젝트(klmin-drive)와 같은 구성이다
- 처음 결정의 이유는 그대로 지켜진다: Alloy 의 scrape 도 pull 이라 죽은 서비스는 `up=0` 으로 남고(`ServiceDown`), 경보 규칙은 Prometheus 에 그대로 둔다. job·라벨(`job="services"`, `service`, `job="kafka"`)도 같아서 경보·대시보드·CI 쿼리를 바꾸지 않았다
- compose 와 k8s 가 같은 `config.alloy` 를 쓴다(CI 가 사본 `diff`). 환경마다 다른 서비스 주소는 `METRICS_SERVICE_TARGETS`(JSON, compose 는 `host.docker.internal:<포트>`, 차트는 `values.services` 로 만든 `<Service>:<포트>`)로 받는다. k8s 에만 있는 수집(cAdvisor)은 차트가 같은 폴더에 `kubernetes.alloy` 를 더 넣고 Alloy 가 폴더째 읽는다(`alloy run /etc/alloy`). cAdvisor 는 API 서버의 노드 프록시로 읽어 ServiceAccount 와 ClusterRole(`nodes`, `nodes/proxy`, `nodes/metrics` 읽기)이 생긴다. 이 namespace 의 컨테이너만, 메모리·limit·CPU·OOM 네 지표만 남긴다
- Prometheus 는 직접 긁지 않으므로 `/targets` 는 비어 있다. 수집 상태는 `up{job="…"}` 이나 Alloy UI(compose http://localhost:12346)로 본다
- 새 경보(그룹 `observability`)
  - `MetricsPipelineDown`: `up{job="alloy"}` 가 1분 동안 없음. **수집기가 하나라 Alloy 가 멈추면 메트릭이 통째로 끊기고 `ServiceDown` 도 울리지 않는다**(판단할 `up` 이 없음). 그래서 "사라짐"을 본다. 관측성 스택 자기 지표는 15초 간격으로 긁는다(기본 60초면 1분 창에서 틈이 생겨 잘못 울릴 수 있다)
  - `ObservabilityTargetDown`: tempo·loki `up=0` 1분
  - `ContainerMemoryNearLimit`: 컨테이너 working set 이 limit 의 90% 를 2분 (k8s 만 값이 있다). OOMKilled 직전 신호
- 확인: 로컬 클러스터의 임시 namespace 에 차트의 관측성 부분만 올려 job 7종(services 7·kafka·tempo·loki·prometheus·alloy·cadvisor)이 들어오고 `service` 라벨이 그대로인 것, Alloy 를 0개로 줄이면 약 40초 뒤 `MetricsPipelineDown` 이 firing 되는 것을 봤다. CI `kind` 잡은 기존처럼 `up{job="services"}`=7·`up{job="kafka"}`=1 을 확인한다
- Alloy 메모리 limit 256Mi → 512Mi (트래픽 없이 ~170Mi, tail sampling 이 trace 를 10초 들고 있다)

**Alloy 가 단일 장애점(SPOF)이 되는 문제**

수집기를 하나로 모으면 Alloy 가 멈출 때 trace·로그·메트릭이 같이 끊긴다. 지금은 Alloy 를 **한 개**로 두고 위 `MetricsPipelineDown` 으로 "끊겼다는 것"을 알리는 데까지 한다. 서비스·Saga 처리는 Alloy 와 무관하게 계속되고, k8s 는 죽은 Pod 를 다시 띄운다(그 사이 메트릭은 빈다).

replicas 만 늘리면 해결되지 않는다.
- 메트릭: 각 Alloy 가 같은 대상을 전부 긁어 같은 시계열이 겹쳐 들어간다. Alloy **clustering**(`--cluster.enabled`, 헤드리스 Service 로 서로 찾기, `prometheus.scrape` 의 `clustering { enabled = true }`)을 켜야 대상을 나눠 긁고, 하나가 죽으면 남은 쪽이 넘겨받는다
- trace: tail sampling 은 한 trace 의 span 이 **같은 Alloy** 에 모여야 판단할 수 있다. 여러 개면 앞단에 trace id 로 나눠 주는 계층(`otelcol.exporter.loadbalancing`)이 필요하다
- 로그: 상태가 없어 Service 뒤에 여러 개를 둬도 된다

운영(EKS)에서 수집 공백이 문제가 되면 위 순서로 HA 를 붙인다(clustering 으로 scrape 분산 → 2단 trace 수집). 노드마다 하나씩 도는 DaemonSet(파일 로그·kubelet 수집)과 중앙 Deployment(OTLP 수신·tail sampling)로 나누는 구성이 흔하다

## 결과

- 좋은 점: Saga 한 건이 서비스·relay 모드와 상관없이 trace 하나로 보인다. 어느 서비스에서 얼마나 걸렸는지, 메시지가 Kafka·relay에서 얼마나 머물렀는지(앞 span 끝 ~ 다음 span 시작)가 보인다. 메커니즘이 outbox 컬럼 하나와 헤더 하나라 단순하다
- 나쁜 점: outbox 스키마에 컬럼이 하나 는다(서비스마다 Flyway). Debezium 커넥터 설정이 그 컬럼을 참조하므로 **서비스(마이그레이션)를 먼저 배포하고 커넥터 설정을 나중에** 바꾼다. 반대 순서면 Event Router가 없는 컬럼 때문에 실패한다(`table.field.additional.missing=error`)
- 한계
  - 문맥 없이 시작하는 작업은 새 trace가 된다: 단계 타임아웃 재전송(`SagaTimeoutService`), 결제 보정 후 응답(`PaymentReconcileService`), traceparent가 없던 예전 메시지. 이때도 리스너 span에 orderId가 붙어 있어 주문 id 검색으로 같은 주문의 trace들을 모아 볼 수 있다. 재전송까지 원래 trace에 잇고 싶다면 Saga 행에 처음 traceparent를 저장해 두는 방법이 있다(필요해지면)
  - DB 쿼리(JDBC) span은 없다. 서비스 안에서 느린 곳은 지금은 처리 span 길이로만 본다
  - Tempo는 로컬 디스크·24시간 보존이다. 운영이면 오브젝트 스토리지와 보존 기간을 정한다. Loki 도 로컬 디스크이고 컨테이너를 내리면 사라진다
  - 로그 appender 는 기동이 끝난 뒤 붙으므로 기동 중 로그는 Loki 에 없다(콘솔에는 있다). trace 문맥 밖의 로그(주기 작업, 기동)는 trace_id 없이 들어간다
  - 경보 문턱값은 시연용으로 짧다(위 표). Prometheus 규칙 파일은 환경 변수를 받지 않으므로 운영에서는 규칙 파일을 따로 둔다
  - 메트릭 게이지는 스크레이프마다 DB 집계 쿼리를 한 번 돈다(2초 캐시). 인스턴스가 여럿이면 같은 값이 인스턴스마다 나오므로 경보는 `max`로 묶는다
