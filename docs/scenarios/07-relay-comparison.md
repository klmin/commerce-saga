# 시나리오 7. relay 비교: 스케줄러 vs Debezium, Saga 완료 지연과 DB 부하

같은 Saga를 두 relay 모드에서 돌려, 주문 생성부터 `COMPLETED`까지 걸린 시간과 DB에 주는 부하를 비교한다. 검증(PASS/FAIL)이 아니라 측정이다. 결론은 [ADR 0004](../adr/0004-debezium-outbox-relay.md).

## 재현

1. 측정할 모드로 네 서비스를 띄운다 (스케줄러: 기본 / Debezium: `OUTBOX_RELAY_ENABLED=false` + `scripts/register-connectors.sh`)
2. `scripts/measure-relay.sh` (기본: 주문 30건, 진행 중 Saga 최대 5건, 유휴 측정 30초)
   - **유휴**: 트래픽 없이 30초 동안 DB 네 개의 커밋 수(`pg_stat_database.xact_commit`)와 outbox 테이블 스캔 수(`pg_stat_user_tables`)
   - **지연**: 사용자 3이 상품 1~3을 돌아가며 1개씩, 포인트 10씩 주문. 5건을 넣고 **5건이 모두 끝나면** 다음 5건을 넣는다(진행 중인 Saga가 5건을 넘지 않음, `CONCURRENCY=1`이면 하나씩 직렬). 지연은 서버 시각 `orders.updated_at − created_at`(`COMPLETED`로 바뀐 시각 − 생성 시각)이라 스크립트의 폴링 간격 오차가 없다
   - **부하 중 커밋**: 지연 측정 구간의 커밋 수에서 같은 길이의 유휴분(유휴 비율 × 구간)을 빼고 주문 수로 나눈다
   - Postgres 누적 통계는 백엔드가 모았다가 늦게 반영한다(쉬는 연결은 최대 10초 뒤). 그래서 스냅숏 전에 11초 기다리고, 지연 측정 종료와 통계 수집을 분리했다. 네 DB의 값은 `docker compose exec` 한 번에 읽어 시점을 맞춘다

k6 대신 bash + curl을 썼다. CI·로컬에 추가 도구 없이 같은 스크립트를 돌리려고. 진행 중 5건 정도로는 처리량 한계가 아니라 relay가 더하는 지연을 본다.

CI `e2e`가 두 모드(matrix)에서 이 스크립트를 마지막 쪽에 돌려 결과를 로그로 남긴다.

## 설정

| 모드 | 발행까지 기다리는 시간을 정하는 설정 |
|---|---|
| 스케줄러 | `outbox.relay.fixed-delay: 200ms` (한 번 발행 후 다음 조회까지 쉬는 시간), 서비스마다 하나 |
| Debezium | 커넥터 `poll.interval.ms` (태스크가 변경을 모아 Connect에 넘기기 전 기다리는 최대 시간). 기본 500ms, 측정 후 **100ms**로 둠 |

Saga 한 건이 `COMPLETED`까지 relay를 6번 거친다: `ReserveStock` → 응답 → `UsePoint` → 응답 → `Pay` → 응답. 그래서 완료 지연 ≈ 처리 시간 + 6 × (relay 한 번의 대기).

## 결과 (로컬)

로컬(Windows, Docker Desktop, 서비스는 호스트 JVM, 워밍업 후) 2026-11-23:

| | 스케줄러 (200ms) | Debezium (poll 500ms, 기본) | Debezium (poll 100ms) |
|---|---|---|---|
| 완료 지연 p50 | **776 ms** | 2449 ms | 811 ms |
| 완료 지연 p95 | 1271 ms | 3656 ms | **922 ms** |
| 유휴 커밋 (DB 4개 합) | 19.6 /s | 1.5 /s | **0.8 /s** |
| 유휴 outbox 스캔 (30초) | 574 | 0 | 0 |
| 부하 중 커밋 / 주문 (유휴분 제외) | 15.1* | 17.5* | 16.5* |

* 로컬의 주문당 커밋은 측정기 자신의 조회(완료 확인 등)가 섞인 값이다(Codex 리뷰로 찾아 고침). 고친 스크립트의 값은 아래 CI 결과를 본다.

```
== 7. relay 측정 (mode=scheduler, orders=30, concurrency=5)
  유휴 (30.7s)
    db               commits  commits/s   outbox scans
    order_db             148        4.8            145
    inventory_db         159        5.2            145
    point_db             146        4.8            139
    payment_db           148        4.8            145
    total                601       19.6            574
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=776  p95=1271  max=1339
  부하 구간 (25.1s, 통계 반영 대기 11s 포함, 유휴분 = 유휴 비율 × 구간)
    db               commits  유휴분    주문당   outbox scans
    order_db             284      120.5          5.5            150
    inventory_db         196      130.5          2.2            131
    point_db             156      120.5          1.2            126
    payment_db           309      120.5          6.3            134
    total                945      492.0         15.1            541

== 7. relay 측정 (mode=debezium, orders=30, concurrency=5)      # poll.interval.ms=100
  유휴 (30.8s)
    db               commits  commits/s   outbox scans
    order_db               5        0.2              0
    inventory_db           7        0.2              0
    point_db               7        0.2              0
    payment_db             5        0.2              0
    total                 24        0.8              0
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=811  p95=922  max=948
  부하 구간 (23.0s, 통계 반영 대기 11s 포함, 유휴분 = 유휴 비율 × 구간)
    db               commits  유휴분    주문당   outbox scans
    order_db             193        4.6          6.3            120
    inventory_db          79        4.6          2.5             30
    point_db              46        4.6          1.4             30
    payment_db           196        4.6          6.4             30
    total                514       18.4         16.5            210
```

참고(버스트): 첫 버전 스크립트는 동시성을 POST에만 걸어 30건이 한꺼번에 진행됐다. 그 조건의 지연(서버 시각이라 값 자체는 유효)은 스케줄러 p50 2083 ms, Debezium(poll 100ms) p50 876 ms, Debezium(poll 500ms) p50 3169 ms였다. 그때의 커밋 수는 통계 반영 지연 때문에 믿을 수 없어 버렸다. 이 문제는 Codex 리뷰(PR #23)에서 지적받아 고쳤다.

## 결과 (CI)

[2026-11-23 실행](https://github.com/klmin/commerce-saga/actions/runs/45125062218) (GitHub Actions ubuntu-latest, 두 모드 matrix, 다른 시나리오를 다 돈 뒤 측정, 측정기 조회 커밋 차감):

| | 스케줄러 (200ms) | Debezium (poll 100ms) |
|---|---|---|
| 완료 지연 p50 / p95 | 1106 / 2277 ms | **821 / 1115 ms** |
| 유휴 커밋 (DB 4개 합) | 20.1 /s | **0.8 /s** |
| 유휴 outbox 스캔 (30초) | 600 | 0 |
| 부하 중 커밋 / 주문 (유휴분·측정기 제외) | 16.2 | 16.1 |

```
== 7. relay 측정 (mode=scheduler, orders=30, concurrency=5)
  유휴 (30.3s)
    total                609       20.1            600
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=1106  p95=2277  max=2306
  부하 구간 (21.3s, 통계 반영 대기 11s 포함, 유휴분 = 유휴 비율 × 구간)
    db               commits  유휴분    주문당   outbox scans
    order_db             292      108.6          6.1            138
    inventory_db         184      106.5          2.6            113
    point_db             145      106.5          1.3            115
    payment_db           294      106.5          6.2            118
    total                915      428.1         16.2            484

== 7. relay 측정 (mode=debezium, orders=30, concurrency=5)
  유휴 (30.1s)
    total                 24        0.8              0
  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)
    completed=30 cancelled=0  p50=821  p95=1115  max=1144
  부하 구간 (18.2s, 통계 반영 대기 11s 포함, 유휴분 = 유휴 비율 × 구간)
    db               commits  유휴분    주문당   outbox scans
    order_db             189        5.5          6.1            120
    inventory_db          77        1.8          2.5             30
    point_db              40        3.6          1.2             30
    payment_db           190        3.6          6.2             30
    total                496       14.5         16.1            210
```

CI(모든 컨테이너·서비스가 러너 한 대)에서는 로컬보다 스케줄러의 지연이 더 길게 나왔고(p50 +26%, p95 2배), 주문당 커밋은 두 모드가 같았다(16.2 대 16.1).

## 해석

- **유휴 부하가 가장 큰 차이다.** 스케줄러는 트래픽이 없어도 서비스마다 200ms마다 `select … for update skip locked` 트랜잭션을 연다(서비스 4개 × 5회/초 ≈ 20 커밋/초, 30초에 outbox 스캔 574회). 인스턴스를 늘리면 그만큼 곱해진다. Debezium은 outbox를 읽지 않는다. 남은 커밋은 heartbeat(10초마다 1회)와 측정 노이즈 정도다
- **지연 p50은 적은 부하에서 크게 다르지 않고, 꼬리 지연은 Debezium이 고르다.** 진행 중 5건에서 로컬 p50은 776 대 811 ms로 차이가 없고, CI에서는 1106 대 821 ms였다. p95는 두 환경 모두 Debezium이 더 고르다(로컬 1271 대 922, CI 2277 대 1115 ms). 스케줄러는 메시지가 언제 들어오느냐에 따라 0~200ms를 더 기다리기 때문이다. 30건이 한꺼번에 몰린 버스트에서는 스케줄러가 2.4배 느렸다(위 참고)
- **스케줄러는 지연과 유휴 부하를 맞바꾼다.** 간격을 줄이면 지연은 줄지만 유휴 커밋이 그만큼 늘어난다(50ms면 ≈ 80 커밋/초). Debezium은 폴링 비용 없이 `poll.interval.ms`만 줄이면 된다. 기본값 500ms로 두면 스케줄러보다 3배 느렸다. 기본값을 쓰지 않은 이유
- **주문당 커밋은 같다(CI 16.2 대 16.1).** 스케줄러가 부하 중 돌리는 조회·삭제 트랜잭션은 어차피 돌던 빈 조회를 대신하므로 유휴분을 빼면 거의 남지 않는다. Debezium 모드의 outbox 스캔은 relay가 아니라 `OutboxWriter`가 같은 트랜잭션에서 넣고 바로 지우는 `delete … where id = ?`의 인덱스 조회다
- 한계: 노트북 한 대에서 모든 것을 돌린 값이라 절대값보다 **모드 간 차이와 그 원인**을 본다. 처리량 한계(초당 수백 건 이상)는 측정하지 않았다. 그 영역에서는 스케줄러의 배치 크기·인스턴스 수, Connect 태스크 하나(Postgres 커넥터는 태스크 하나만 가능)가 병목 후보다
