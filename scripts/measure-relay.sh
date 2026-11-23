#!/usr/bin/env bash
# 시나리오 7. relay 모드별 Saga 완료 지연·DB 부하 측정 (docs/scenarios/07-relay-comparison.md)
#
# 준비: 네 서비스를 측정할 relay 모드로 띄운다 (scheduler: 기본 / debezium: OUTBOX_RELAY_ENABLED=false + 커넥터 등록)
# 실행:
#   scripts/measure-relay.sh            # 결과를 표 형태로 출력. 검증(PASS/FAIL)이 아니라 측정용
#
# 측정하는 것
#   1. 유휴 부하: 트래픽 없이 IDLE_SECONDS 동안 DB 네 개의 커밋 수·outbox 테이블 스캔 수 (relay 폴링 비용)
#   2. 지연: 진행 중인 Saga 를 최대 CONCURRENCY 건으로 유지하며 주문 ORDERS 건. 한 묶음(CONCURRENCY 건)을 넣고
#      모두 끝난 뒤 다음 묶음을 넣는다 (CONCURRENCY=1 이면 Saga 하나씩 직렬). 주문 생성 → COMPLETED 시간은
#      서버 시각(orders.updated_at - created_at)이라 스크립트의 폴링 간격 오차가 없다
#   3. 부하 중 커밋: 2 의 구간 커밋 수에서 측정기 자신의 조회(완료 확인 등) 커밋과 같은 길이의 유휴분(1 의 비율)을
#      뺀 값을 주문 수로 나눈다
#
# Postgres 누적 통계(pg_stat_*)는 백엔드가 모아 두었다가 늦게 반영한다 (쉬는 연결은 최대 10초 뒤).
# 그래서 스냅숏 전에 STATS_SETTLE_SECONDS 만큼 기다린다. 지연 측정 종료와 통계 수집 시점은 분리돼 있다
#
# k6 대신 bash + curl 을 쓴다 (CI·로컬에 추가 도구 없이 같은 방식으로 돌리려고)
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
ORDERS=${ORDERS:-30}
CONCURRENCY=${CONCURRENCY:-5}
USER_ID=${USER_ID:-3}
IDLE_SECONDS=${IDLE_SECONDS:-30}
STATS_SETTLE_SECONDS=${STATS_SETTLE_SECONDS:-11}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
DBS="order_db inventory_db point_db payment_db"

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# 측정기 자신이 서비스 DB 에 여는 트랜잭션(psql -c 한 번 = 커밋 하나)을 DB 별로 세어 측정값에서 뺀다
probe() { echo >> "$tmp/probe_$1"; }
probes() { [ -f "$tmp/probe_$1" ] && wc -l < "$tmp/probe_$1" | tr -d " " || echo 0; }
reset_probes() { rm -f "$tmp"/probe_*; }
psql_q() { probe "$1"; docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD=postgres postgres psql -U postgres -d "$1" -tAc "$2"; }
now_s() { date +%s.%N | cut -c1-14; }
calc() { awk "BEGIN{printf \"%.1f\", $1}"; }

snapshot() { # -> "db:commits:scans ..." (커밋 수, outbox 테이블을 읽은 횟수 seq + index). 시점을 맞추려고 exec 한 번에 읽는다
  local db
  for db in $DBS; do probe "$db"; done   # outbox 스캔 수를 읽는 조회가 그 DB 에 커밋 하나를 남긴다
  docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD=postgres postgres sh -c '
    for db in '"$DBS"'; do
      c=$(psql -U postgres -d postgres -tAc "select xact_commit from pg_stat_database where datname = '"'"'$db'"'"'")
      s=$(psql -U postgres -d "$db" -tAc "select coalesce(seq_scan, 0) + coalesce(idx_scan, 0) from pg_stat_user_tables where relname = '"'"'outbox'"'"'")
      printf " %s:%s:%s" "$db" "$c" "$s"
    done'
}
delta() { # before after db col(2=commits,3=scans)
  local b a
  b=$(echo "$1" | tr ' ' '\n' | grep "^$3:" | cut -d: -f"$4")
  a=$(echo "$2" | tr ' ' '\n' | grep "^$3:" | cut -d: -f"$4")
  echo $(( a - b ))
}

curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
  -d '{"mode":"APPROVE","delayMillis":0}' > /dev/null

mode=$(psql_q postgres "select case when count(*) filter (where active) > 0 then 'debezium' else 'scheduler' end from pg_replication_slots")
echo "== 7. relay 측정 (mode=$mode, orders=$ORDERS, concurrency=$CONCURRENCY)"

# 1. 유휴 부하 (앞선 작업의 통계가 반영된 뒤 시작)
sleep "$STATS_SETTLE_SECONDS"
reset_probes
t0=$(now_s); before=$(snapshot)
sleep "$IDLE_SECONDS"
declare -A idle_probes
for db in $DBS; do idle_probes[$db]=$(probes "$db"); done
t1=$(now_s); after=$(snapshot)
idle_secs=$(calc "$t1 - $t0")
echo "  유휴 (${idle_secs}s)"
printf '    %-13s %10s %10s %14s\n' db commits "commits/s" "outbox scans"
declare -A idle_rate_c idle_rate_s
total_c=0; total_s=0
for db in $DBS; do
  dc=$(( $(delta "$before" "$after" "$db" 2) - idle_probes[$db] )); ds=$(delta "$before" "$after" "$db" 3)
  idle_rate_c[$db]=$(calc "$dc / $idle_secs"); idle_rate_s[$db]=$(calc "$ds / $idle_secs")
  total_c=$(( total_c + dc )); total_s=$(( total_s + ds ))
  printf '    %-13s %10d %10s %14d\n' "$db" "$dc" "${idle_rate_c[$db]}" "$ds"
done
printf '    %-13s %10d %10s %14d\n' total "$total_c" "$(calc "$total_c / $idle_secs")" "$total_s"

# 2. 지연: 묶음 단위로 넣고 그 묶음이 끝나면 다음 묶음 (진행 중인 Saga 최대 CONCURRENCY 건)
sleep "$STATS_SETTLE_SECONDS"
mark=$(psql_q order_db "select now()")
reset_probes
t0=$(now_s); before=$(snapshot)
sent=0
while [ "$sent" -lt "$ORDERS" ]; do
  batch=$(( ORDERS - sent < CONCURRENCY ? ORDERS - sent : CONCURRENCY ))
  rm -rf "$tmp/ids"; mkdir -p "$tmp/ids"   # 주문 id 파일만 정리 (probe_* 카운터는 구간 끝까지 유지)
  for j in $(seq 1 "$batch"); do
    product=$(( ((sent + j) % 3) + 1 ))
    curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" \
      -d "{\"lines\":[{\"productId\":$product,\"quantity\":1,\"unitPrice\":100}],\"pointAmount\":10}" \
      | field orderId > "$tmp/ids/$j" &
  done
  wait
  ids=$(for f in "$tmp"/ids/*; do id=$(cat "$f"); [ -n "$id" ] && printf "'%s'\n" "$id"; done | paste -sd, -)
  [ "$(echo "$ids" | tr ',' '\n' | grep -c .)" -eq "$batch" ] || { echo "FAIL: 주문 생성 실패" >&2; exit 1; }
  deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while [ "$(psql_q order_db "select count(*) from orders where id in ($ids) and status in ('COMPLETED', 'CANCELLED')")" -lt "$batch" ]; do
    if [ "$(date +%s)" -gt "$deadline" ]; then
      echo "FAIL: ${TIMEOUT_SECONDS}초 안에 묶음이 끝나지 않음" >&2
      exit 1
    fi
    sleep 0.2
  done
  sent=$(( sent + batch ))
done

echo "  주문 → COMPLETED 지연 (ms, 서버 시각 orders.updated_at - created_at)"
psql_q order_db "
  select format('    completed=%s cancelled=%s  p50=%s  p95=%s  max=%s',
    count(*) filter (where status = 'COMPLETED'), count(*) filter (where status = 'CANCELLED'),
    round(percentile_cont(0.5) within group (order by ms) filter (where status = 'COMPLETED')),
    round(percentile_cont(0.95) within group (order by ms) filter (where status = 'COMPLETED')),
    round(max(ms) filter (where status = 'COMPLETED')))
  from (select status, extract(epoch from (updated_at - created_at)) * 1000 as ms
        from orders where created_at >= '$mark') t"

# 3. 부하 중 커밋: 통계 반영을 기다린 뒤 수집하고, 구간 길이만큼의 유휴분을 뺀다
sleep "$STATS_SETTLE_SECONDS"
declare -A load_probes
for db in $DBS; do load_probes[$db]=$(probes "$db"); done
t1=$(now_s); after=$(snapshot)
win=$(calc "$t1 - $t0")
echo "  부하 구간 (${win}s, 통계 반영 대기 ${STATS_SETTLE_SECONDS}s 포함, 유휴분 = 유휴 비율 × 구간)"
printf '    %-13s %10s %10s %12s %14s\n' db commits "유휴분" "주문당" "outbox scans"
total_c=0; total_net=0; total_s=0
for db in $DBS; do
  dc=$(delta "$before" "$after" "$db" 2); ds=$(delta "$before" "$after" "$db" 3)
  dc=$(( dc - load_probes[$db] ))   # 측정기의 완료 확인·지연 조회 커밋 제외
  net=$(calc "$dc - ${idle_rate_c[$db]} * $win")
  total_c=$(( total_c + dc )); total_s=$(( total_s + ds )); total_net=$(calc "$total_net + $net")
  printf '    %-13s %10d %10s %12s %14d\n' "$db" "$dc" "$(calc "${idle_rate_c[$db]} * $win")" "$(calc "$net / $ORDERS")" "$ds"
done
printf '    %-13s %10d %10s %12s %14d\n' total "$total_c" "$(calc "$total_c - $total_net")" "$(calc "$total_net / $ORDERS")" "$total_s"
