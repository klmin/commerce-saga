# 시나리오 7. relay 비교: 스케줄러 vs Debezium, Saga 완료 지연과 DB 부하

같은 Saga를 두 relay 모드에서 돌려, 주문 생성부터 `COMPLETED`까지 걸린 시간과 DB에 주는 부하를 비교한다. 검증(PASS/FAIL)이 아니라 측정이다. 결론은 [ADR 0004](../adr/0004-debezium-outbox-relay.md).

## 재현

1. 측정할 모드로 네 서비스를 띄운다 (스케줄러: 기본 / Debezium: `OUTBOX_RELAY_ENABLED=false` + `scripts/register-connectors.sh`)
2. `scripts/measure-relay.sh` (기본: 주문 30건, 동시 5건, 유휴 측정 30초)
   - **유휴**: 트래픽 없이 30초 동안 DB 네 개의 커밋 수(`pg_stat_database.xact_commit`)와 outbox 테이블 스캔 수(`pg_stat_user_tables`)
   - **부하 중**: 사용자 3이 상품 1~3을 돌아가며 1개씩, 포인트 10씩 주문 30건. 지연은 서버 시각 `orders.updated_at − created_at`(`COMPLETED`로 바뀐 시각 − 생성 시각)이라 스크립트의 폴링 간격 오차가 없다

k6 대신 bash + curl을 썼다. CI·로컬에 추가 도구 없이 같은 스크립트를 돌리려고. 동시 5건 정도로는 처리량 한계가 아니라 relay가 더하는 지연을 본다.

CI `e2e`가 두 모드(matrix)에서 이 스크립트를 마지막 쪽에 돌려 결과를 로그로 남긴다.

## 설정

| 모드 | 발행까지 기다리는 시간을 정하는 설정 |
|---|---|
| 스케줄러 | `outbox.relay.fixed-delay: 200ms` (한 번 발행 후 다음 조회까지 쉬는 시간), 서비스마다 하나 |
| Debezium | 커넥터 `poll.interval.ms` (태스크가 변경을 모아 Connect에 넘기기 전 기다리는 최대 시간). 기본 500ms, 측정 후 **100ms**로 둠 |

Saga 한 건에 relay를 거치는 메시지가 7개다: `ReserveStock` → 응답 → `UsePoint` → 응답 → `Pay` → 응답 → `OrderCompleted`(완료 시각에는 안 들어감). 그래서 완료 지연 ≈ 처리 시간 + 6 × (relay 한 번의 대기).

## 결과 (로컬)

로컬(Windows, Docker Desktop, 서비스는 호스트 JVM) 2026-11-23:

| | 스케줄러 (200ms) | Debezium (poll 500ms, 기본) | Debezium (poll 100ms) |
|---|---|---|---|
| 완료 지연 p50 | 2083 ms | 3169 ms | **876 ms** |
| 완료 지연 p95 | 2397 ms | 3347 ms | **963 ms** |
| 유휴 커밋 (DB 4개 합) | **23.5 /s** | — | 1.3 /s |
| 유휴 outbox 스캔 (30초) | **675** | 0 | 0 |
| 부하 중 커밋 (주문 1건당) | 10.9 | — | 3.9 |

Debezium poll 500ms는 지연만 따로 본 것이라 부하 열은 비웠다.

```
== 7. relay 측정 (mode=scheduler, orders=30, concurrency=5)
  유휴 (30s)
    db               commits  commits/s   outbox scans
    order_db             178        5.9            170
    inventory_db         180        6.0            165
    point_db             174        5.8            170
    payment_db           174        5.8            170
    total                706       23.5            675
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=2083  p95=2397  max=2604
  부하 중 (4s)
    total                328       82.0            187

== 7. relay 측정 (mode=debezium, orders=30, concurrency=5)      # poll.interval.ms=100
  유휴 (30s)
    db               commits  commits/s   outbox scans
    order_db              19        0.6              0
    inventory_db           5        0.2              0
    point_db               5        0.2              0
    payment_db             9        0.3              0
    total                 38        1.3              0
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=876  p95=963  max=1114
  부하 중 (2s)
    total                117       58.5             57
    주문 1건당 커밋 3.9
```

## 결과 (CI)

(CI 실행 후 채움)

## 해석

- **유휴 부하**: 스케줄러는 트래픽이 없어도 서비스마다 200ms마다 `select … for update skip locked` 트랜잭션을 연다(서비스 4개 × 5회/초 ≈ 20 커밋/초, outbox 스캔 675회/30초). 인스턴스를 늘리면 그만큼 곱해진다. Debezium은 outbox를 읽지 않는다. 남은 커밋은 heartbeat(10초마다 1회)와 측정 자체의 노이즈 정도다
- **지연**: 스케줄러 지연은 폴링 간격에 묶인다. 간격을 줄이면 지연은 줄지만 유휴 부하가 그만큼 늘어난다(50ms면 ≈ 80 커밋/초). Debezium은 WAL을 스트리밍으로 받으므로 폴링 비용 없이 `poll.interval.ms`만 줄이면 된다. 기본값 500ms로 두면 오히려 스케줄러보다 느렸다(3.2초). 기본값을 그대로 쓰지 않은 이유
- **부하 중 커밋**: 스케줄러는 비즈니스 트랜잭션 외에 "조회 + 발행 후 삭제" 트랜잭션이 더 든다. Debezium 모드의 outbox 스캔(57)은 relay가 아니라 `OutboxWriter`가 같은 트랜잭션에서 넣고 바로 지우는 `delete … where id = ?`의 인덱스 조회다
- 한계: 노트북 한 대에서 모든 것을 돌린 값이라 절대값보다 **모드 간 차이와 그 원인**을 본다. 처리량 한계(초당 수백 건 이상)는 측정하지 않았다. 그 영역에서는 스케줄러의 배치 크기·인스턴스 수, Connect 태스크 하나(커넥터당 `tasks.max=1`, Postgres 커넥터는 하나만 가능)가 병목 후보다
