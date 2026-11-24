#!/usr/bin/env bash
# 시나리오 13. 가입 → 포인트 계정 (auth.events UserRegistered, 코레오그래피)
# (docs/scenarios/13-signup-point-account.md)
#
# 준비: 인프라, 다섯 서비스, auth-service(8086), gateway(8000). Debezium 모드면 커넥터 등록(auth-outbox 포함)
# 실행: scripts/smoke-signup-points.sh
#
# 확인하는 것
#   13a. 새로 가입한 사용자에게 포인트 계정이 생기고 잔액 = 가입 축하 포인트(기본 1,000P)
#   13b. 그 사용자가 포인트를 써서 주문 → COMPLETED, 잔액이 쓴 만큼 줄어든다 (전에는 계정이 없어 보상으로 끝났다)
#   13c. 계정은 사용자 하나에 하나 (point_db 의 그 사용자 행 1개), 다른 사용자 계정은 그대로
#   13d. 포인트 내역(GET /api/points/me/history) = 최신순으로 주문 사용, 가입 축하 지급. 지급 기록(point_grants)은 1개
set -euo pipefail

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
SIGNUP_BONUS=${SIGNUP_BONUS:-1000}
USE_POINT=300
PASSWORD=${PASSWORD:-signup-pass-1}
COMPOSE=${COMPOSE:-docker compose -f infra/docker-compose.yml}

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
# Gateway rate limit(auth 경로는 IP 별)에 걸리면 기다렸다 다시 보낸다. 429 는 서비스에 넘기기 전에 거절한 것이라 안전
call() { # method path [curl args...] → "<status>\n<body>"
  local method=$1 path=$2 out code i
  shift 2
  for i in $(seq 1 30); do
    out=$(curl -s -w '\n%{http_code}' -X "$method" "$GATEWAY_URL$path" "$@")
    code=${out##*$'\n'}
    [ "$code" = 429 ] || { printf '%s\n%s' "$code" "${out%$'\n'*}"; return 0; }
    sleep 1
  done
  printf '429\n'
}
status_of() { head -1; }
body_of() { tail -n +2; }
psql_point() { $COMPOSE exec -T -e PGPASSWORD=postgres postgres psql -U postgres -d point_db -tAc "$1"; }

login_id="signup_$(date +%s)_$$"
login_id=${login_id:0:30}

echo "== 13a. 가입하면 포인트 계정이 생긴다"
res=$(call POST /auth/signup -H 'Content-Type: application/json' \
  -d "{\"loginId\":\"$login_id\",\"password\":\"$PASSWORD\"}")
[ "$(status_of <<< "$res")" = 201 ] || fail "가입 → $(status_of <<< "$res")"
user_id=$(body_of <<< "$res" | field userId)
[ -n "$user_id" ] || fail "가입 응답에 userId 없음"
ok "가입 $login_id → userId $user_id"
res=$(call POST /auth/login -H 'Content-Type: application/json' -H 'User-Agent: commerce-saga-scripts' \
  -d "{\"loginId\":\"$login_id\",\"password\":\"$PASSWORD\"}")
token=$(body_of <<< "$res" | field accessToken)
[ -n "$token" ] || fail "로그인 실패 ($(status_of <<< "$res"))"
auth=(-H "Authorization: Bearer $token")

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
started=$(date +%s%3N)
while :; do
  res=$(call GET /api/points/me "${auth[@]}")
  [ "$(status_of <<< "$res")" = 200 ] && break
  [ "$(status_of <<< "$res")" = 404 ] || fail "포인트 조회 → $(status_of <<< "$res")"
  [ "$(date +%s)" -le "$deadline" ] || fail "${TIMEOUT_SECONDS}초 안에 포인트 계정이 안 생김"
  sleep 0.5
done
balance=$(body_of <<< "$res" | field balance)
[ "$balance" = "$SIGNUP_BONUS" ] || fail "새 계정 잔액 $balance (기대 $SIGNUP_BONUS)"
ok "포인트 계정 생성 $(( $(date +%s%3N) - started ))ms 안에 확인, 잔액 ${balance}P (가입 축하)"

echo "== 13b. 그 포인트로 주문"
res=$(call POST /api/orders "${auth[@]}" -H 'Content-Type: application/json' \
  -d "{\"lines\":[{\"productId\":1,\"quantity\":1,\"unitPrice\":1000}],\"pointAmount\":$USE_POINT}")
[ "$(status_of <<< "$res")" = 201 ] || fail "주문 → $(status_of <<< "$res")"
order_id=$(body_of <<< "$res" | field orderId)
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  s=$(call GET "/api/orders/$order_id" "${auth[@]}" | body_of | field status)
  [ "$s" = COMPLETED ] && break
  [ "$s" = CANCELLED ] && fail "주문이 CANCELLED (포인트 계정이 없거나 부족?)"
  [ "$(date +%s)" -le "$deadline" ] || fail "주문이 COMPLETED 가 안 됨 ($s)"
  sleep 1
done
balance=$(call GET /api/points/me "${auth[@]}" | body_of | field balance)
[ "$balance" = $(( SIGNUP_BONUS - USE_POINT )) ] || fail "주문 후 잔액 $balance (기대 $(( SIGNUP_BONUS - USE_POINT )))"
ok "주문 …${order_id: -8} COMPLETED, 잔액 ${SIGNUP_BONUS} → ${balance}P"

echo "== 13c. 계정은 하나"
rows=$(psql_point "select count(*) from point_accounts where user_id = $user_id")
[ "$rows" = 1 ] || fail "point_accounts 에 사용자 $user_id 행이 $rows 개"
ok "point_accounts 사용자 $user_id 행 1개"
seed=$(psql_point "select count(*) from point_accounts where user_id between 1 and 4")
[ "$seed" = 4 ] || fail "시드 계정 수가 $seed"
ok "시드 계정(1~4) 그대로"

echo "== 13d. 포인트 내역 = 주문 사용 + 가입 축하 지급 한 번 (F-2a)"
res=$(call GET "/api/points/me/history?size=20" "${auth[@]}")
[ "$(status_of <<< "$res")" = 200 ] || fail "포인트 내역 → $(status_of <<< "$res")"
history=$(body_of <<< "$res")
first=$(sed -E 's/\},\{/}\n{/g' <<< "$history" | head -1)
second=$(sed -E 's/\},\{/}\n{/g' <<< "$history" | sed -n 2p)
[ "$(grep -o '"entryId"' <<< "$history" | wc -l)" = 2 ] || fail "내역 항목 수가 2 가 아님: $history"
[ "$(field kind <<< "$first")" = ORDER ] && [ "$(field orderId <<< "$first")" = "$order_id" ] \
  && [ "$(field amount <<< "$first")" = "$USE_POINT" ] && [ "$(field status <<< "$first")" = USED ] \
  || fail "최신 항목이 주문 사용이 아님: $first"
[ "$(field kind <<< "$second")" = SIGNUP_BONUS ] && [ "$(field amount <<< "$second")" = "$SIGNUP_BONUS" ] \
  || fail "두 번째 항목이 가입 축하 지급이 아님: $second"
grants=$(psql_point "select count(*) from point_grants where user_id = $user_id")
[ "$grants" = 1 ] || fail "point_grants 에 사용자 $user_id 행이 $grants 개"
ok "내역: 주문 −${USE_POINT}P(USED) → 가입 축하 +${SIGNUP_BONUS}P, 지급 기록 1개"

echo "PASS: 시나리오 13 (가입 → 포인트 계정)"
