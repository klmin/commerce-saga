# 시나리오 5. Debezium 커넥터 중단 → 재개 후 유실 0, slot WAL 적체와 heartbeat

Debezium relay 모드에서 Kafka Connect(커넥터)가 멈춰도 메시지가 사라지지 않고, 재개하면 멈춘 자리부터 이어서 발행하는지 확인한다. 그동안 replication slot이 WAL을 붙잡아 적체가 늘어나는 것, heartbeat로 트래픽 없는 DB의 slot도 앞으로 가는 것도 함께 본다.

## 재현

1. Debezium relay 모드로 띄운다 ([PROGRESS 7절](../PROGRESS.md#7-로컬에서-이어서-돌리기)): 네 서비스 `OUTBOX_RELAY_ENABLED=false`, `scripts/register-connectors.sh`
2. `scripts/smoke-connector-outage.sh`
   - 커넥터 네 개를 `PUT /connectors/<이름>/stop`으로 멈춘다 (STOPPED, 태스크 종료, slot 비활성)
   - 사용자 3이 상품 2를 1개씩, 포인트 100씩 써서 주문 5건
   - 15초 기다린 뒤 주문 상태·outbox·포인트·slot 적체 확인
   - `PUT /connectors/<이름>/resume` → 주문 완료·포인트·재고·slot 적체 확인

CI `e2e`의 `saga (debezium)`가 다른 시나리오 뒤에 이 스크립트를 돈다.

## 기대 결과

| 시점 | 기대 |
|---|---|
| 중단 중 | 주문 5건 모두 `PENDING` (커맨드가 나가지 않음). 포인트 그대로 |
| 중단 중 | `order_db.outbox` 0행. 넣고 바로 지우므로 메시지는 **WAL에만** 있다 |
| 중단 중 | `order_outbox_slot` `active = f`, `confirmed_flush_lsn`이 멈춰 적체(현재 WAL 위치 − 확인 위치)가 늘어난다 |
| 재개 후 | 5건 모두 `COMPLETED`. 포인트 −500, 상품 2 available −5, reserved 원복 (정확히 한 번 반영) |
| 재개 후 | slot 적체가 중단 중보다 줄어든다 (발행 확인 + heartbeat) |

## 실제 결과

로컬(Windows, Docker Desktop) 실행:

```
== 5. 커넥터 중단 → 주문 → 재개 후 유실 0
  ok  커넥터 STOPPED, order_outbox_slot active=f
  주문 5건 생성, 15초 동안 커넥터 중단 유지
  ok  중단 중 주문 …(5건) status = PENDING
  ok  중단 중 order_db outbox 행 수 (넣고 바로 지움) = 0
  ok  중단 중 포인트 잔액 (변화 없음) = 8800
  slot 적체 (order_outbox_slot): 중단 전 5304 B → 중단 중 13488 B
    inventory_outbox_slot|f|154 kB
    order_outbox_slot|f|13 kB
    payment_outbox_slot|f|27 kB
    point_outbox_slot|f|93 kB
  ok  재개 후 주문 5건 모두 COMPLETED (1769ms)
  ok  포인트 잔액 (주문 수만큼 한 번씩) = 8300
  ok  상품 2 available = 55
  ok  상품 2 reserved (판매 확정) = 0
  ok  재개 후 slot 적체 13488 B → 4200 B
PASS
```

CI `e2e` `saga (debezium)` ([2026-11-23 실행](https://github.com/klmin/commerce-saga/actions/runs/45125062218), GitHub Actions ubuntu-latest, 다른 시나리오를 다 돈 뒤):

```
== 5. 커넥터 중단 → 주문 → 재개 후 유실 0
  ok  커넥터 STOPPED, order_outbox_slot active=f
  주문 5건 생성, 15초 동안 커넥터 중단 유지
  ok  중단 중 주문 …(5건) status = PENDING
  ok  중단 중 order_db outbox 행 수 (넣고 바로 지움) = 0
  ok  중단 중 포인트 잔액 (변화 없음) = 10000
  slot 적체 (order_outbox_slot): 중단 직후 2128 B → 중단 중 10248 B
  ok  재개 후 주문 5건 모두 COMPLETED (1818ms)
  ok  포인트 잔액 (주문 수만큼 한 번씩) = 9500
  ok  상품 2 available = 95
  ok  상품 2 reserved (판매 확정) = 0
  ok  재개 후 slot 적체 10248 B → 7312 B
PASS
```

로컬 결과의 "중단 전" 값은 커넥터를 멈추기 전에 읽은 것이고, 지금 스크립트는 멈춘 직후에 읽는다(Codex 리뷰 반영).

heartbeat 효과 (로컬, 별도 확인): 다른 DB(`postgres`)에 약 20 MB의 WAL을 만든 뒤 네 slot 모두 적체가 21 MB로 늘었다. heartbeat를 켠 inventory 커넥터만 20초 안에 **96 B**로 돌아왔고, 아직 켜지 않은 나머지 셋은 21 MB 그대로였다.

## 왜 이렇게 되나

- 커넥터는 slot의 `confirmed_flush_lsn`(어디까지 Kafka에 보냈는지)을 Kafka 발행 ack 이후에만 앞으로 옮긴다. 멈춘 동안 Postgres는 그 위치 이후의 WAL을 지우지 않는다. 재개하면 그 위치부터 다시 디코딩하므로 커밋된 outbox INSERT는 하나도 빠지지 않는다
- 이미 보냈지만 확인 위치를 아직 옮기지 못한 메시지는 재개 뒤 **다시** 나갈 수 있다(at-least-once). 메시지 `id`가 outbox `id` 그대로라 컨슈머의 `processed_message`가 걸러낸다 (ADR 0006). 포인트·재고가 주문 수만큼만 바뀐 것이 그 결과다
- **WAL은 클러스터 전체에서 하나다.** slot은 자기 DB의 변경만 받지만 붙잡는 WAL은 모든 DB의 것이다. 그래서 커넥터가 살아 있어도 자기 DB(그중 outbox)에 변경이 없으면 확인 위치가 안 움직이고, 다른 DB의 트래픽만큼 적체가 쌓인다. 위의 21 MB가 그 경우다
- heartbeat: `heartbeat.interval.ms=10000` + `heartbeat.action.query = select pg_logical_emit_message(false, 'heartbeat', …)`. 10초마다 자기 DB에 논리 메시지를 하나 남겨 커넥터가 받을 변경을 만들고, heartbeat 레코드(`__debezium-heartbeat.<커넥터>` 토픽)와 함께 위치를 확인한다. 테이블을 새로 만들지 않아도 되고 outbox publication도 건드리지 않는다. 논리 메시지 자체는 `message.prefix.exclude.list=heartbeat`로 발행하지 않는다
- 커넥터가 **멈춰 있는 동안**에는 heartbeat도 돌지 않는다. 그때 적체는 막을 수 없으므로 디스크가 차기 전에 알아채야 한다 → slot 적체 경보(후속 관측성). Postgres 13+의 `max_slot_wal_keep_size`로 상한을 둘 수 있지만, 넘으면 slot이 무효화되어 **그 사이 메시지를 잃는다**. 상한을 둘지는 운영 단계에서 경보와 함께 정한다 (ADR 0004)
