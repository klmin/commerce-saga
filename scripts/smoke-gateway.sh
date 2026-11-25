#!/usr/bin/env bash
# 시나리오 9 (Gateway·소유권 부분). JWT 로 바꾼 뒤에도 남의 주문을 볼 수 없고, Gateway 와 서비스가 각각 토큰을 검증하는지
# (docs/scenarios/09-auth-refresh-reuse.md)
#
# 준비: 인프라, 다섯 서비스, auth-service(8086), gateway(8000). rate limit 을 확인하려면 gateway 를 낮게 띄운다
#   GATEWAY_USER_RATE=5 GATEWAY_USER_BURST=10
# 실행: scripts/smoke-gateway.sh
#
# 확인하는 것
#   9f. 토큰 없이 Gateway → 401, 서비스에 직접 → 401 (서비스도 다시 검증)
#   9g. 사용자 1 이 Gateway 로 주문 → 사용자 1 은 조회 200, 사용자 2 는 주문·Saga·결제 모두 404 (IDOR)
#       사용자 2 가 X-User-Id: 1 을 붙여도 404 (헤더는 Gateway 가 지우고, 서비스는 토큰의 sub 만 본다)
#   9h. 관리자 경로(/api/admin/payment-faults): 일반 사용자 403, 관리자 200
#   9i. 같은 사용자가 몰아서 요청하면 429 (사용자별 rate limit), 다른 사용자는 그대로 200
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
ORDER_URL=${ORDER_URL:-http://localhost:8081}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect_status() { # expected description curl-args...
  local expected=$1 what=$2 got
  shift 2
  got=$(status "$@")
  [ "$got" = "$expected" ] || fail "$what → $got (기대 $expected)"
  ok "$what → $got"
}
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }

echo "== 9f. 토큰 없으면 Gateway 도 서비스도 401"
expect_status 401 "Gateway GET /api/orders/… (토큰 없음)" "$GATEWAY_URL/api/orders/00000000-0000-7000-8000-000000000000"
expect_status 401 "order-service 직접 (토큰 없음)" "$ORDER_URL/orders/00000000-0000-7000-8000-000000000000"
expect_status 401 "Gateway (위조 토큰)" "$GATEWAY_URL/api/points/me" -H 'Authorization: Bearer abc.def.ghi'

echo "== 9g. 사용자 1 의 주문을 사용자 2 가 볼 수 없다"
order_id=$(curl -sf -X POST "$GATEWAY_URL/api/orders" -H "$(auth 1)" -H 'Content-Type: application/json' \
  -d '{"lines":[{"productId":1,"quantity":1,"unitPrice":39000}],"pointAmount":0}' | field orderId)
[ -n "$order_id" ] || fail "Gateway 로 주문 생성 실패"
ok "사용자 1 주문 …${order_id: -8} (Gateway 경유)"
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
until [ "$(curl -sf "$GATEWAY_URL/api/orders/$order_id" -H "$(auth 1)" | field status)" = "COMPLETED" ]; do
  [ "$(date +%s)" -le "$deadline" ] || fail "주문이 COMPLETED 가 안 됨"
  sleep 1
done
ok "사용자 1 조회 → COMPLETED"
expect_status 404 "사용자 2 → 사용자 1 주문" "$GATEWAY_URL/api/orders/$order_id" -H "$(auth 2)"
expect_status 404 "사용자 2 → 사용자 1 Saga" "$GATEWAY_URL/api/orders/$order_id/saga" -H "$(auth 2)"
expect_status 404 "사용자 2 → 사용자 1 결제" "$GATEWAY_URL/api/payments/orders/$order_id" -H "$(auth 2)"
expect_status 404 "사용자 2 + X-User-Id: 1 헤더" "$GATEWAY_URL/api/orders/$order_id" -H "$(auth 2)" -H 'X-User-Id: 1'
expect_status 404 "사용자 2 + X-User-Id: 1 헤더, 서비스 직접" "$ORDER_URL/orders/$order_id" -H "$(auth 2)" -H 'X-User-Id: 1'

echo "== 9h. 관리자 경로는 ADMIN 만"
expect_status 403 "사용자 1 → /api/admin/payment-faults" "$GATEWAY_URL/api/admin/payment-faults" -H "$(auth 1)"
expect_status 200 "관리자 → /api/admin/payment-faults" "$GATEWAY_URL/api/admin/payment-faults" -H "$(auth 100)"

echo "== 9i. 사용자별 rate limit"
token3=$(auth 3)
codes=""
for _ in $(seq 1 30); do
  codes="$codes $(status "$GATEWAY_URL/api/points/me" -H "$token3")"
done
ok200=$(echo "$codes" | tr ' ' '\n' | grep -c '^200$' || true)
limited=$(echo "$codes" | tr ' ' '\n' | grep -c '^429$' || true)
[ "$limited" -gt 0 ] || fail "30번 연속 요청에 429 가 없음 ($codes)"
ok "사용자 3 연속 30번 → 200 ${ok200}번, 429 ${limited}번"
expect_status 200 "그동안 사용자 4 는 그대로" "$GATEWAY_URL/api/points/me" -H "$(auth 4)"

echo "PASS"
