#!/usr/bin/env bash
# 시나리오 10. 주문 Idempotency-Key: 결제 버튼 두 번·새로고침·응답 유실 후 재시도에도 주문은 하나
# 그리고 내 주문·결제 목록 (docs/scenarios/10-order-idempotency.md)
#
# 준비: 인프라, 다섯 서비스, auth-service(8086), gateway(8000)
# 실행: scripts/smoke-idempotency.sh
#
# 확인하는 것
#   10a. 같은 키로 두 번 → 201 다음 200(Idempotent-Replayed: true), 같은 orderId
#   10b. 같은 키로 동시에 5번 → 모두 같은 orderId, 새 주문은 하나 (내 주문 목록 맨 위 두 건이 10b·10a 주문)
#   10c. 같은 키 + 다른 본문 → 422, 주문 안 생김
#   10d. 두 주문 모두 COMPLETED, 포인트는 주문마다 한 번씩만 차감 (Saga 도 주문마다 하나)
#   10e. 내 주문·결제 목록: 최신순 커서 페이징, 다른 사용자에게는 안 보임
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
USER_ID=${USER_ID:-3}
OTHER_USER_ID=${OTHER_USER_ID:-4}
POINT=100

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
ids() { grep -o "\"$1\":\"[^\"]*\"" | sed -E 's/.*:"([^"]*)"/\1/'; }
# Gateway 사용자별 rate limit(CI 는 초당 5·버스트 10, 바로 앞 smoke-gateway 가 사용자 3 버킷을 비운다)에 걸리면
# 기다렸다 다시 보낸다. 429 는 Gateway 가 서비스에 넘기기 전에 거절한 것이라 다시 보내도 안전하다
RETRIES_429=${RETRIES_429:-30}
get() { # path userId → 본문 (200 이 아니면 실패)
  local i out code
  for i in $(seq 1 "$RETRIES_429"); do
    out=$(curl -s -w '\n%{http_code}' "$GATEWAY_URL$1" -H "$(auth "$2")")
    code=${out##*$'\n'}
    if [ "$code" = 429 ]; then sleep 1; continue; fi
    [ "$code" = 200 ] || fail "GET $1 (사용자 $2) → $code"
    printf '%s' "${out%$'\n'*}"
    return 0
  done
  fail "GET $1 (사용자 $2) → 계속 429"
}
points() { get /api/points/me "$1" | field balance; }
body() { # quantity
  printf '{"lines":[{"productId":2,"quantity":%s,"unitPrice":25000}],"pointAmount":%s}' "$1" "$POINT"
}
place() { # key quantity → "status orderId replayed"
  local out headers status i
  out=$(mktemp)
  for i in $(seq 1 "$RETRIES_429"); do
    headers=$(curl -s -o "$out" -D - -X POST "$GATEWAY_URL/api/orders" -H "$(auth "$USER_ID")" \
      -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$(body "$2")")
    status=$(printf '%s' "$headers" | sed -nE 's#^HTTP/[0-9.]+ ([0-9]+).*#\1#p' | tail -1)
    [ "$status" = 429 ] || break
    sleep 1
  done
  printf '%s %s %s\n' "$status" \
    "$(field orderId < "$out")" \
    "$(printf '%s' "$headers" | tr -d '\r' | sed -nE 's/^[Ii]dempotent-[Rr]eplayed: (.*)$/\1/p')"
  rm -f "$out"
}
wait_completed() { # orderId
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS )) status
  while :; do
    status=$(get "/api/orders/$1" "$USER_ID" | field status)
    [ "$status" = "COMPLETED" ] && return 0
    [ "$status" = "CANCELLED" ] && fail "주문 …${1: -8} 이 CANCELLED"
    [ "$(date +%s)" -le "$deadline" ] || fail "주문 …${1: -8} 이 COMPLETED 가 안 됨 ($status)"
    sleep 1
  done
}

points_before=$(points "$USER_ID")
[ -n "$points_before" ] || fail "사용자 $USER_ID 포인트 조회 실패"
run=$(date +%s)-$$

echo "== 10a. 같은 키로 두 번"
key_a="smoke-a-$run"
read -r s1 order_a r1 <<< "$(place "$key_a" 1)"
[ "$s1" = 201 ] && [ -n "$order_a" ] || fail "첫 요청 → $s1 (기대 201)"
ok "첫 요청 → 201, 주문 …${order_a: -8}"
read -r s2 order_a2 r2 <<< "$(place "$key_a" 1)"
[ "$s2" = 200 ] || fail "같은 키 재요청 → $s2 (기대 200)"
[ "$order_a2" = "$order_a" ] || fail "같은 키인데 다른 주문 $order_a2"
[ "$r2" = "true" ] || fail "Idempotent-Replayed 헤더 없음"
ok "재요청 → 200, 같은 주문, Idempotent-Replayed: true"

echo "== 10b. 같은 키로 동시에 5번"
key_b="smoke-b-$run"
results=$(mktemp)
for _ in 1 2 3 4 5; do
  place "$key_b" 1 >> "$results" &
done
wait
order_b=$(awk 'NR==1{print $2}' "$results")
[ "$(awk '{print $2}' "$results" | sort -u | wc -l)" = 1 ] || { cat "$results"; fail "동시 요청이 서로 다른 주문을 받음"; }
[ "$(awk '$1==201' "$results" | wc -l)" = 1 ] || { cat "$results"; fail "201 이 정확히 1번이 아님"; }
[ "$(awk '$1==200' "$results" | wc -l)" = 4 ] || { cat "$results"; fail "200(재요청) 이 4번이 아님"; }
rm -f "$results"
ok "5개 모두 주문 …${order_b: -8}, 201 한 번 + 200 네 번"
latest=$(get "/api/orders?size=2" "$USER_ID" | ids orderId | tr '\n' ' ')
[ "$latest" = "$order_b $order_a " ] || fail "내 주문 목록 맨 위가 [$order_b $order_a] 가 아님: [$latest]"
ok "내 주문 목록 맨 위 두 건 = 10b, 10a (다른 주문이 생기지 않음)"

echo "== 10c. 같은 키 + 다른 본문 → 422"
read -r s3 _ _ <<< "$(place "$key_a" 2)"
[ "$s3" = 422 ] || fail "다른 본문 → $s3 (기대 422)"
[ "$(get "/api/orders?size=1" "$USER_ID" | ids orderId)" = "$order_b" ] \
  || fail "422 뒤에 주문이 생김"
ok "다른 본문 → 422, 주문 안 생김"

echo "== 10d. 두 주문 COMPLETED, 포인트는 주문마다 한 번"
wait_completed "$order_a"
wait_completed "$order_b"
ok "두 주문 COMPLETED"
points_after=$(points "$USER_ID")
[ "$points_after" = $(( points_before - 2 * POINT )) ] \
  || fail "포인트 $points_before → $points_after (기대 $(( points_before - 2 * POINT )), 주문 2건 × $POINT)"
ok "포인트 $points_before → $points_after (2건 × $POINT, 재요청은 차감 없음)"

echo "== 10e. 내 주문·결제 목록"
second=$(get "/api/orders?size=1&before=$order_b" "$USER_ID" | ids orderId)
[ "$second" = "$order_a" ] || fail "커서 다음 쪽이 $order_a 가 아님: $second"
ok "주문 목록 커서(before=10b 주문) → 10a 주문"
payments=$(get "/api/payments?size=5" "$USER_ID")
printf '%s' "$payments" | ids orderId | grep -qx "$order_b" || fail "결제 목록에 10b 주문 결제 없음"
printf '%s' "$payments" | ids orderId | grep -qx "$order_a" || fail "결제 목록에 10a 주문 결제 없음"
[ "$(printf '%s' "$payments" | ids orderId | grep -cx "$order_b")" = 1 ] || fail "10b 주문 결제가 두 건 이상"
ok "결제 목록에 두 주문 결제가 하나씩"
# 조회 실패가 "안 보임" 으로 넘어가지 않게 먼저 받아 둔다 (if 안의 파이프는 set -e 가 듣지 않는다)
other_orders=$(get "/api/orders?size=50" "$OTHER_USER_ID")
other_payments=$(get "/api/payments?size=50" "$OTHER_USER_ID")
if printf '%s' "$other_orders" | ids orderId | grep -qx "$order_b"; then
  fail "사용자 $OTHER_USER_ID 주문 목록에 사용자 $USER_ID 주문이 보임"
fi
if printf '%s' "$other_payments" | ids orderId | grep -qx "$order_b"; then
  fail "사용자 $OTHER_USER_ID 결제 목록에 사용자 $USER_ID 결제가 보임"
fi
ok "사용자 $OTHER_USER_ID 의 주문·결제 목록에는 안 보임"

echo "PASS: 시나리오 10 (주문 Idempotency-Key, 내 주문·결제 목록)"
