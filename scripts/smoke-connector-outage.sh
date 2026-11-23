#!/usr/bin/env bash
# 시나리오 5. Debezium 커넥터 중단 → 재개 후 유실 0 (docs/scenarios/05-connector-outage.md)
#
# 준비: Debezium relay 모드로 띄운 상태 (docs/PROGRESS.md 7절)
#   - 네 서비스를 OUTBOX_RELAY_ENABLED=false 로 기동, scripts/register-connectors.sh 로 커넥터 등록
# 실행:
#   scripts/smoke-connector-outage.sh
#
# 확인하는 것
#   1. 커넥터 네 개를 멈추면(STOPPED) slot 이 비활성이 되고, 그동안 낸 주문은 PENDING 에서 멈춘다
#      outbox 테이블은 비어 있다 (넣고 바로 지움). 메시지는 WAL 에만 있고 slot 이 그 WAL 을 붙잡아 적체가 늘어난다
#   2. 커넥터를 재개하면 slot 의 마지막 확인 위치부터 다시 읽어 멈췄던 주문이 모두 COMPLETED 가 된다 (유실 0)
#      포인트·재고는 주문 수만큼 정확히 한 번 반영된다 (재개 때 다시 나간 메시지는 컨슈머가 걸러낸다)
#   3. heartbeat 로 적체가 다시 줄어든다
set -euo pipefail

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
CONNECT_URL=${CONNECT_URL:-http://localhost:8088}
CONNECTORS=${CONNECTORS:-"order-outbox inventory-outbox point-outbox payment-outbox"}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
ORDERS=${ORDERS:-5}
USER_ID=${USER_ID:-3}
PRODUCT_ID=${PRODUCT_ID:-2}
POINT_AMOUNT=${POINT_AMOUNT:-100}
OUTAGE_SECONDS=${OUTAGE_SECONDS:-15}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
stock() { curl -sf "$INVENTORY_URL/stocks/$1"; }
points() { curl -sf "$POINT_URL/points/me" -H "X-User-Id: $1" | field balance; }
order_status() { curl -sf "$ORDER_URL/orders/$2" -H "X-User-Id: $1" | field status; }
psql_q() { # db sql
  docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD=postgres postgres psql -U postgres -d "$1" -tAc "$2"
}
connector_state() { curl -sf "$CONNECT_URL/connectors/$1/status" | sed -nE 's/.*"connector":\{"state":"([A-Z]+)".*/\1/p'; }
slot_lag_bytes() { psql_q postgres "select pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)::bigint from pg_replication_slots where slot_name = '$1'"; }
slot_active() { psql_q postgres "select active from pg_replication_slots where slot_name = '$1'"; }

expect_eq() {
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 expected '$2' but was '$3'" >&2
    exit 1
  fi
  echo "  ok  $1 = $3"
}

wait_until() { # description command...
  local what=$1 deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  shift
  while [ "$(date +%s)" -le "$deadline" ]; do
    if "$@"; then return 0; fi
    sleep 0.5
  done
  echo "FAIL: timeout waiting for $what" >&2
  return 1
}

all_connectors() { # state
  local c
  for c in $CONNECTORS; do [ "$(connector_state "$c")" = "$1" ] || return 1; done
}
slot_inactive() { [ "$(slot_active order_outbox_slot)" = "f" ]; }
all_completed() {
  local id
  for id in $order_ids; do [ "$(order_status "$USER_ID" "$id")" = "COMPLETED" ] || return 1; done
}

resume_all() {
  local c
  for c in $CONNECTORS; do curl -sf -X PUT "$CONNECT_URL/connectors/$c/resume" > /dev/null || true; done
}
# 실패해도 커넥터는 다시 돌려 둔다
trap resume_all EXIT

curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H 'Content-Type: application/json' \
  -d '{"mode":"APPROVE","delayMillis":0}' > /dev/null

echo "== 5. 커넥터 중단 → 주문 → 재개 후 유실 0"
avail_before=$(stock "$PRODUCT_ID" | field available)
reserved_before=$(stock "$PRODUCT_ID" | field reserved)
points_before=$(points "$USER_ID")
lag_before=$(slot_lag_bytes order_outbox_slot)

for c in $CONNECTORS; do curl -sf -X PUT "$CONNECT_URL/connectors/$c/stop" > /dev/null; done
wait_until "connectors STOPPED" all_connectors STOPPED
wait_until "order slot inactive" slot_inactive
echo "  ok  커넥터 STOPPED, order_outbox_slot active=f"

order_ids=""
for i in $(seq 1 "$ORDERS"); do
  body="{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":1000}],\"pointAmount\":$POINT_AMOUNT}"
  id=$(curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "X-User-Id: $USER_ID" -d "$body" | field orderId)
  order_ids="$order_ids $id"
done
echo "  주문 $ORDERS건 생성, ${OUTAGE_SECONDS}초 동안 커넥터 중단 유지"
sleep "$OUTAGE_SECONDS"

for id in $order_ids; do
  expect_eq "중단 중 주문 …${id: -8} status" PENDING "$(order_status "$USER_ID" "$id")"
done
expect_eq "중단 중 order_db outbox 행 수 (넣고 바로 지움)" 0 "$(psql_q order_db 'select count(*) from outbox')"
expect_eq "중단 중 포인트 잔액 (변화 없음)" "$points_before" "$(points "$USER_ID")"
lag_during=$(slot_lag_bytes order_outbox_slot)
echo "  slot 적체 (order_outbox_slot): 중단 전 ${lag_before} B → 중단 중 ${lag_during} B"
if [ "$lag_during" -le "$lag_before" ]; then
  echo "FAIL: 중단 중 slot 적체가 늘지 않음" >&2
  exit 1
fi
psql_q postgres "select slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) from pg_replication_slots order by slot_name" | sed 's/^/    /'

resume_start=$(date +%s%3N)
resume_all
wait_until "connectors RUNNING" all_connectors RUNNING
wait_until "orders COMPLETED" all_completed
echo "  ok  재개 후 주문 $ORDERS건 모두 COMPLETED ($(( $(date +%s%3N) - resume_start ))ms)"

expect_eq "포인트 잔액 (주문 수만큼 한 번씩)" $(( points_before - ORDERS * POINT_AMOUNT )) "$(points "$USER_ID")"
expect_eq "상품 $PRODUCT_ID available" $(( avail_before - ORDERS )) "$(stock "$PRODUCT_ID" | field available)"
reserved_back() { [ "$(stock "$PRODUCT_ID" | field reserved)" = "$reserved_before" ]; }
wait_until "stock confirmed" reserved_back
expect_eq "상품 $PRODUCT_ID reserved (판매 확정)" "$reserved_before" "$(stock "$PRODUCT_ID" | field reserved)"

# heartbeat(10초)가 돌면 confirmed_flush_lsn 이 최근 위치로 온다. 다른 DB 의 WAL 이 계속 쌓여도 중단 중보다 작아진다
lag_drained() { [ "$(slot_lag_bytes order_outbox_slot)" -lt "$lag_during" ]; }
wait_until "slot lag drained" lag_drained
echo "  ok  재개 후 slot 적체 ${lag_during} B → $(slot_lag_bytes order_outbox_slot) B"

echo "PASS"
