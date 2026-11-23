#!/usr/bin/env bash
# 시나리오 9 (인증 부분). refresh token 재사용 탐지 → 그 기기 세션 폐기 (docs/scenarios/09-auth-refresh-reuse.md)
#
# 준비: 인프라(postgres, redis)와 auth-service(8086)를 띄운다.
#   동시 refresh 유예를 짧게 두면 빨리 끝난다: AUTH_REFRESH_GRACE=2s (기본 20s) → GRACE_WAIT=3
# 실행: scripts/smoke-auth.sh
#
# 확인하는 것
#   9a. 가입 → 로그인: access token 은 본문, refresh token 은 httpOnly 쿠키. access token 의 kid 가 JWKS 에 있다
#   9b. refresh 하면 새 refresh token 으로 회전. 유예 안에 직전 토큰이 오면(여러 탭 동시 refresh) access token 만 준다
#   9c. 유예가 지난 뒤 옛 토큰을 다시 쓰면(탈취 의심) 401, 그 family 의 새 토큰도 함께 무효
#   9d. 로그아웃한 기기의 refresh token 은 401. 다른 기기는 그대로
#   9e. 같은 아이디로 5번 틀리면 맞는 비밀번호도 429 (Retry-After)
set -euo pipefail

AUTH_URL=${AUTH_URL:-http://localhost:8086}
GRACE_WAIT=${GRACE_WAIT:-21}
PASSWORD='smoke-pass-1'
# 쿠키로 인증하는 refresh·로그아웃은 이 헤더가 있어야 한다 (CSRF 방어, ADR 0008)
CSRF='X-Requested-With: fetch'

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }

# 응답 헤더 + 본문을 파일 두 개로. 상태 코드를 출력한다
call() { # method path [curl args...]
  local method=$1 path=$2
  shift 2
  curl -s -o /tmp/smoke-auth.body -D /tmp/smoke-auth.headers -w '%{http_code}' -X "$method" "$AUTH_URL$path" "$@"
}
expect() { # expected-status method path [curl args...]
  local expected=$1 status
  shift
  status=$(call "$@")
  [ "$status" = "$expected" ] || fail "$1 $2 → $status (기대 $expected): $(cat /tmp/smoke-auth.body)"
}
body_field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" /tmp/smoke-auth.body; }
set_cookie() { tr -d '\r' < /tmp/smoke-auth.headers | sed -nE 's/^[Ss]et-[Cc]ookie: refresh_token=([^;]*);.*/\1/p'; }
cookie() { printf 'Cookie: refresh_token=%s' "$1"; }
login() { # loginId agent
  expect 200 POST /auth/login -H 'Content-Type: application/json' -H "User-Agent: $2" \
    -d "{\"loginId\":\"$1\",\"password\":\"$PASSWORD\"}"
}
b64url_decode() { local s=$1; s=$(printf '%s' "$s" | tr '_-' '/+'); while [ $(( ${#s} % 4 )) -ne 0 ]; do s="$s="; done; printf '%s' "$s" | base64 -d 2>/dev/null; }

user="smoke_$(date +%s)_$RANDOM"

echo "== 9a. 가입 → 로그인 → JWKS"
expect 201 POST /auth/signup -H 'Content-Type: application/json' -d "{\"loginId\":\"$user\",\"password\":\"$PASSWORD\"}"
user_id=$(body_field userId)
expect 409 POST /auth/signup -H 'Content-Type: application/json' -d "{\"loginId\":\"$user\",\"password\":\"$PASSWORD\"}"
ok "가입 userId=$user_id, 같은 아이디 409"

login "$user" laptop
access=$(body_field accessToken)
rt1=$(set_cookie)
[ -n "$access" ] && [ -n "$rt1" ] || fail "로그인 응답에 토큰이 없음"
tr -d '\r' < /tmp/smoke-auth.headers | grep -i '^set-cookie: refresh_token=' | grep -q 'HttpOnly' || fail "refresh 쿠키가 HttpOnly 가 아님"
grep -q '"refreshToken"' /tmp/smoke-auth.body && fail "본문에 refresh token 이 있음"
payload=$(b64url_decode "$(printf '%s' "$access" | cut -d. -f2)")
kid=$(b64url_decode "$(printf '%s' "$access" | cut -d. -f1)" | sed -nE 's/.*"kid":"([^"]+)".*/\1/p')
printf '%s' "$payload" | grep -q "\"sub\":\"$user_id\"" || fail "access token sub 가 사용자 id 가 아님: $payload"
expect 200 GET /.well-known/jwks.json
grep -q "\"kid\":\"$kid\"" /tmp/smoke-auth.body || fail "JWKS 에 kid=$kid 없음"
grep -q '"d":' /tmp/smoke-auth.body && fail "JWKS 에 개인키 성분이 있음"
ok "access token(sub=$user_id, kid=$kid) 본문, refresh token httpOnly 쿠키, JWKS 에 같은 kid"

expect 200 GET /auth/sessions -H "Authorization: Bearer $access" -H "$(cookie "$rt1")"
grep -q '"current":true' /tmp/smoke-auth.body || fail "기기 목록에 현재 기기 표시 없음"
expect 401 GET /auth/sessions
ok "기기 목록(access token 필요, 없으면 401)"

echo "== 9b. refresh 회전, 동시 refresh 유예"
expect 403 POST /auth/refresh -H "$(cookie "$rt1")"
ok "CSRF 헤더 없는 refresh → 403 (쿠키만으로는 안 됨)"
expect 200 POST /auth/refresh -H "$CSRF" -H "$(cookie "$rt1")"
rt2=$(set_cookie)
[ -n "$rt2" ] && [ "$rt2" != "$rt1" ] || fail "refresh 후 새 refresh token 이 없음"
[ "${rt2%%.*}" = "${rt1%%.*}" ] || fail "회전했는데 family 가 바뀜"
family=${rt1%%.*}
ok "rt1 → rt2 회전 (같은 family …${family: -8})"
expect 200 POST /auth/refresh -H "$CSRF" -H "$(cookie "$rt1")"
[ -z "$(set_cookie)" ] || fail "유예 안의 직전 토큰인데 쿠키를 다시 줌"
[ -n "$(body_field accessToken)" ] || fail "유예 응답에 access token 이 없음"
ok "유예 안에 rt1 다시 → access token 만 (쿠키 그대로)"

echo "== 9c. 유예가 지난 뒤 옛 토큰 재사용 → family 폐기 (${GRACE_WAIT}초 대기)"
sleep "$GRACE_WAIT"
expect 401 POST /auth/refresh -H "$CSRF" -H "$(cookie "$rt1")"
ok "rt1 재사용 → 401"
expect 401 POST /auth/refresh -H "$CSRF" -H "$(cookie "$rt2")"
ok "같은 family 의 rt2 도 401 (다시 로그인해야 함)"

echo "== 9d. 로그아웃은 그 기기만"
login "$user" phone
phone=$(set_cookie)
login "$user" tablet
tablet=$(set_cookie)
expect 204 POST /auth/logout -H "$CSRF" -H "$(cookie "$phone")"
expect 401 POST /auth/refresh -H "$CSRF" -H "$(cookie "$phone")"
expect 200 POST /auth/refresh -H "$CSRF" -H "$(cookie "$tablet")"
ok "phone 로그아웃 → phone 401, tablet 은 refresh 200"

echo "== 9e. 로그인 실패 5번 → 잠금"
for _ in 1 2 3 4 5; do
  expect 401 POST /auth/login -H 'Content-Type: application/json' -d "{\"loginId\":\"$user\",\"password\":\"wrong-password\"}"
done
expect 429 POST /auth/login -H 'Content-Type: application/json' -d "{\"loginId\":\"$user\",\"password\":\"$PASSWORD\"}"
retry_after=$(tr -d '\r' < /tmp/smoke-auth.headers | sed -nE 's/^[Rr]etry-[Aa]fter: ([0-9]+)/\1/p')
[ -n "$retry_after" ] || fail "429 에 Retry-After 없음"
ok "틀린 비밀번호 5번 → 맞는 비밀번호도 429 (Retry-After ${retry_after}s)"

echo "PASS"
