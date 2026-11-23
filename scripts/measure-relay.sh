#!/usr/bin/env bash
# 시나리오 7. relay 모드별 Saga 완료 지연·DB 부하 측정 (docs/scenarios/07-relay-comparison.md)
#
# 준비: 네 서비스를 측정할 relay 모드로 띄운다 (scheduler: 기본 / debezium: OUTBOX_RELAY_ENABLED=false + 커넥터 등록)
# 실행:
#   scripts/measure-relay.sh            # 결과를 표 형태로 출력. 검증(PASS/FAIL)이 아니라 측정용
#
# 측정하는 것
#   1. 유휴 부하: 트래픽 없이 IDLE_SECONDS 동안 DB 네 개의 커밋 수·outbox 테이블 스캔 수 (relay 폴링 비용)
#   2. 부하 중: 주문 ORDERS 건을 CONCURRENCY 개씩 동시에 넣고, 주문 생성 → COMPLETED 까지 걸린 시간
#      (orders.updated_at - created_at, 서버 시각이라 폴링 간격 오차가 없다) 과 그동안의 DB 커밋 수
# k6 대신 bash + curl 을 쓴다 (CI·로컬에 추가 도구 없이 같은 방식으로 돌리려고)
set -euo pipefail

ORDER_URL=${ORDER_URL:-http://localhost:8081}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
ORDERS=${ORDERS:-30}
CONCURRENCY=${CONCURRENCY:-5}
USER_ID=${USER_ID:-3}
IDLE_SECONDS=${IDLE_SECONDS:-30}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-120}
DBS="order_db inventory_db point_db payment_db"

psql_q() { docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD=postgres postgres psql -U postgres -d "$1" -tAc "$2"; }

commits() { # db -> xact_commit
  psql_q postgres "select xact_commit from pg_stat_database where datname = '$1'"
}
outbox_scans() { # db -> outbox 테이블을 읽은 횟수 (seq + index)
  psql_q "$1" "select coalesce(seq_scan, 0) + coalesce(idx_scan, 0) from pg_stat_user_tables where relname = 'outbox'"
}
snapshot() { # -> "db:commits:scans ..."
  local db out=""
  for db in $DBS; do out="$out $db:$(commits "$db"):$(outbox_scans "$db")"; done
  echo "$out"
}
print_delta() { # title seconds before after
  local title=$1 secs=$2 before=$3 after=$4 db b a total_c=0 total_s=0
  echo "  $title (${secs}s)"
  printf '    %-13s %10s %10s %14s\n' db commits "commits/s" "outbox scans"
  for db in $DBS; do
    b=$(echo "$before" | tr ' ' '\n' | grep "^$db:")
    a=$(echo "$after" | tr ' ' '\n' | grep "^$db:")
    local dc=$(( $(echo "$a" | cut -d: -f2) - $(echo "$b" | cut -d: -f2) ))
    local ds=$(( $(echo "$a" | cut -d: -f3) - $(echo "$b" | cut -d: -f3) ))
    total_c=$(( total_c + dc )); total_s=$(( total_s + ds ))
    printf '    %-13s %10d %10s %14d\n' "$db" "$dc" "$(awk "BEGIN{printf \"%.1f\", $dc/$secs}")" "$ds"
  done
  printf '    %-13s %10d %10s %14d\n' total "$total_c" "$(awk "BEGIN{printf \"%.1f\", $total_c/$secs}")" "$total_s"
  last_total_commits=$total_c
}

curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H 'Content-Type: application/json' \
  -d '{"mode":"APPROVE","delayMillis":0}' > /dev/null

mode=$(psql_q postgres "select case when count(*) filter (where active) > 0 then 'debezium' else 'scheduler' end from pg_replication_slots")
echo "== 7. relay 측정 (mode=$mode, orders=$ORDERS, concurrency=$CONCURRENCY)"

# 1. 유휴 부하
before=$(snapshot)
sleep "$IDLE_SECONDS"
after=$(snapshot)
print_delta "유휴" "$IDLE_SECONDS" "$before" "$after"

# 2. 부하 중 지연
mark=$(psql_q order_db "select now()")
before=$(snapshot)
start=$(date +%s)
n=0
for i in $(seq 1 "$ORDERS"); do
  product=$(( (i % 3) + 1 ))
  curl -sf -o /dev/null -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "X-User-Id: $USER_ID" \
    -d "{\"lines\":[{\"productId\":$product,\"quantity\":1,\"unitPrice\":100}],\"pointAmount\":10}" &
  n=$(( n + 1 ))
  if [ "$n" -ge "$CONCURRENCY" ]; then wait; n=0; fi
done
wait

done_sql="select count(*) from orders where created_at >= '$mark' and status in ('COMPLETED', 'CANCELLED')"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while [ "$(psql_q order_db "$done_sql")" -lt "$ORDERS" ]; do
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "FAIL: ${TIMEOUT_SECONDS}초 안에 주문이 끝나지 않음 ($(psql_q order_db "$done_sql")/$ORDERS)" >&2
    exit 1
  fi
  sleep 0.5
done
elapsed=$(( $(date +%s) - start ))
after=$(snapshot)

echo "  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)"
psql_q order_db "
  select format('    completed=%s cancelled=%s  p50=%s  p95=%s  max=%s',
    count(*) filter (where status = 'COMPLETED'), count(*) filter (where status = 'CANCELLED'),
    round(percentile_cont(0.5) within group (order by ms) filter (where status = 'COMPLETED')),
    round(percentile_cont(0.95) within group (order by ms) filter (where status = 'COMPLETED')),
    round(max(ms) filter (where status = 'COMPLETED')))
  from (select status, extract(epoch from (updated_at - created_at)) * 1000 as ms
        from orders where created_at >= '$mark') t"
print_delta "부하 중" "$(( elapsed > 0 ? elapsed : 1 ))" "$before" "$after"
echo "    주문 1건당 커밋 $(awk "BEGIN{printf \"%.1f\", $last_total_commits/$ORDERS}")"
