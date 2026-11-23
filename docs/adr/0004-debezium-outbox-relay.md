# ADR 0004. Outbox relay: 운영 기준은 Debezium(CDC), 코드 기본값은 스케줄러 유지

- 상태: 채택
- 날짜: 2026-11-23

## 배경

모든 메시지는 outbox를 거친다(비즈니스 변경 + outbox INSERT = 한 트랜잭션). outbox 행을 Kafka로 옮기는 relay가 두 가지 있다 (3-C-1, PR #20).

| | 스케줄러 relay (`libs/outbox`) | Debezium relay (Kafka Connect + Outbox Event Router) |
|---|---|---|
| 읽는 곳 | outbox 테이블을 주기적으로 조회 (`FOR UPDATE SKIP LOCKED`) | WAL (logical replication slot, `pgoutput`) |
| 발행 후 | ack를 기다린 뒤 행 삭제 | 서비스가 같은 트랜잭션에서 넣고 바로 지움. Debezium은 INSERT만 발행 |
| 순서 | `created_at`(트랜잭션 시작 시각) 순. 인스턴스가 여럿이면 같은 키도 바뀔 수 있음 | WAL **커밋 순서** 그대로 |
| 필요한 것 | 서비스만 | Kafka Connect, slot·publication, replication 권한 |

브리프는 처음부터 "스케줄러로 시작해 Debezium으로 교체"였다. 3-C-1에서 두 모드를 같은 코드로 고를 수 있게 만들었고(`outbox.relay.enabled`), 이 ADR은 측정과 장애 시나리오를 근거로 어느 쪽을 기준으로 삼을지 정한다.

## 근거

### 측정 (시나리오 7)

진행 중인 Saga 최대 5건, 주문 30건, 로컬:

| | 스케줄러 (200ms) | Debezium (poll 500ms, 기본) | Debezium (poll 100ms) |
|---|---|---|---|
| Saga 완료 지연 p50 / p95 | 776 / 1271 ms | 2449 / 3656 ms | 811 / **922** ms |
| 유휴 커밋 (DB 4개 합) | 19.6 /s | 1.5 /s | **0.8 /s** (heartbeat) |
| 유휴 outbox 스캔 (30초) | 574 | 0 | 0 |
| 부하 중 커밋 / 주문 (유휴분 제외) | 15.1 | 17.5 | 16.5 |

- **가장 큰 차이는 유휴 부하다.** 스케줄러는 트래픽이 없어도 서비스마다 초당 5번 outbox를 조회한다. 인스턴스를 늘리면 그만큼 곱해진다
- 적은 부하에서 지연 p50은 비슷하고, p95는 Debezium이 더 고르다. 30건이 한꺼번에 몰린 버스트에서는 스케줄러 p50이 2083 ms, Debezium(poll 100ms)이 876 ms였다
- 스케줄러는 **지연과 유휴 부하를 맞바꾼다.** 지연을 줄이려 간격을 줄이면 트래픽이 없어도 DB 조회가 그만큼 는다. Debezium은 폴링하지 않으므로 `poll.interval.ms`를 줄여도 DB 부하가 늘지 않는다. 단 **기본값 500ms로는 스케줄러보다 3배 느렸다.** 커넥터에 `poll.interval.ms=100`을 둔다
- 주문당 커밋은 두 모드가 비슷하다. Debezium으로 바꿔 줄어드는 것은 주문 처리 비용이 아니라 트래픽과 무관하게 드는 폴링 비용이다

### 장애 (시나리오 5)

- 커넥터를 멈춘 동안의 메시지는 WAL에 남고, 재개하면 slot 위치부터 다시 읽어 **유실 0**. 다시 나간 메시지는 `id`가 같아 컨슈머가 걸러 **중복 반영 0** (ADR 0006)
- 스케줄러에서 같은 상황(Kafka 장애 등)은 outbox 행이 테이블에 쌓이는 것으로 보인다. Debezium에서는 **slot이 WAL을 붙잡는** 것으로 나타나고, WAL은 클러스터 전체 것이라 다른 DB까지 영향을 준다

### 순서

스케줄러는 `created_at` 순으로 읽어 커밋 순서와 다를 수 있고, 인스턴스가 여럿이면 같은 주문의 메시지가 서로 다른 인스턴스에서 나가 순서가 바뀔 수 있다 (ADR 0005의 알려진 한계). Debezium은 WAL 커밋 순서로 나가므로 서비스 인스턴스 수와 무관하게 순서가 지켜진다.

## 결정

1. **운영(배포) 기준 relay는 Debezium**이다. CI e2e는 두 모드를 모두 돌리고, 장애 시나리오(5)는 Debezium 기준으로 증명한다. 이후 k8s 배포(후속 3)도 Debezium 모드로 구성한다
2. **코드 기본값(`outbox.relay.enabled=true`, 스케줄러)은 바꾸지 않는다.** Debezium 모드는 outbox를 넣고 바로 지우므로, Connect 없이 그 모드로 띄우면 메시지가 **조용히 발행되지 않는다**(`DebeziumSlotCheck`가 경고만 남김). 기본값은 잘못 띄워도 메시지를 잃지 않는 쪽이어야 한다. 배포 설정에서 `OUTBOX_RELAY_ENABLED=false`를 명시한다. 로컬 개발·단위 테스트는 Connect 없이 스케줄러로 돈다
3. 커넥터 설정 (`infra/connect/<서비스>-outbox.json`)
   - `poll.interval.ms=100` — 지연 (위 측정)
   - `heartbeat.interval.ms=10000` + `heartbeat.action.query = select pg_logical_emit_message(false, 'heartbeat', …)` + `message.prefix.exclude.list=heartbeat` — outbox 변경이 없는 DB에서도 slot 확인 위치가 10초마다 앞으로 간다. 별도 heartbeat 테이블·publication 변경 없이 논리 메시지로 처리하고, 메시지 자체는 발행하지 않는다. heartbeat 토픽 `__debezium-heartbeat.<커넥터>`는 `kafka-init`이 만든다(브로커 자동 생성은 꺼 둠)

## 결과와 위험

- 좋은 점: 유휴 DB 부하가 사실상 0, 지연이 폴링 간격에 묶이지 않음(꼬리 지연이 고르고 버스트에 강함), 같은 키 순서가 인스턴스 수와 무관
- 비용: Kafka Connect를 운영해야 한다(배포·모니터링·버전을 브로커와 맞추기). 커넥터·slot이 서비스 DB마다 하나
- **위험 1. slot WAL 적체**: 커넥터가 멈추면 slot이 WAL을 붙잡아 Postgres 디스크가 찬다. 멈춘 동안에는 heartbeat도 소용없다. 대응: slot 적체(`pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)`) 경보를 후속 관측성에서 붙인다. `max_slot_wal_keep_size`로 상한을 둘 수 있지만 넘으면 slot이 무효화되어 **그 사이 메시지를 잃으므로** 지금은 두지 않는다(유실보다 디스크 경보 후 조치를 택함)
- **위험 2. 없는 토픽으로 라우팅되면 커넥터가 조용히 멈춘다**: 로컬에서 실제로 겪었다. 3-C-1 때 테스트로 넣은 `aggregatetype = 'x'` 행 하나 때문에 order 커넥터가 약 2.5시간 동안 아무것도 발행하지 못했는데, 상태는 계속 `RUNNING`이었다. Connect 프로듀서의 `max.block.ms`가 사실상 무한이라 토픽 메타데이터를 기다리며 막힌 것이다(브로커 자동 생성은 꺼 둠). 같은 행은 스케줄러 모드에서도 배치를 계속 롤백시켜 outbox 전체를 막는다. 즉 두 모드 모두 "해당 서비스의 발행 정지"이고 차이는 보이는 방식이다. 대응: 토픽 이름은 코드 상수(`messaging-contract`)로만 쓰고 사람이 outbox에 직접 넣지 않는다. 막힘은 위의 slot 적체 경보로 잡힌다(heartbeat도 같은 태스크라 함께 멈춰 적체가 늘어난다). `producer.override.max.block.ms`로 태스크를 FAILED로 만드는 방법도 있지만, 그러면 브로커 일시 장애에도 태스크가 죽어 수동 재시작이 필요해져 택하지 않았다
- **위험 3. 모드 전환 시 중복**: 스케줄러로 돌던 동안의 outbox INSERT도 WAL에 있어, 커넥터를 다시 켜면 이미 보낸 메시지가 다시 나간다(로컬에서 확인, 결과는 컨슈머가 걸러 변화 없음). 전환은 커넥터 삭제·slot 정리 또는 그 중복을 감수하는 것으로 한다 (PROGRESS 7절)
- **위험 4. slot 생성 전 쓰기**: 서비스가 slot보다 먼저 트래픽을 받으면 그 메시지는 발행되지 않는다. 배포 순서(서비스 기동 → 커넥터 등록 → 트래픽)를 지키고, 기동 시 `DebeziumSlotCheck` 경고를 본다

## 다시 검토할 때

- Connect 운영이 부담인 환경(작은 팀, 관리형 Kafka에 Connect가 없음)이면 스케줄러로 돌아가도 정합성은 같다. 지연과 유휴 부하만 다르다
- 처리량이 커서 Postgres 커넥터의 단일 태스크가 병목이 되면, 서비스 DB를 더 나누거나 스케줄러 relay를 키 범위로 분할하는 방안을 비교한다
