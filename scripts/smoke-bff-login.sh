#!/usr/bin/env bash
# 브라우저 없이 BFF 로그인 한 바퀴 (ADR 0017, 설계 문서 4절): 화면 주소 → Gateway 로그인 시작 → SAS 로그인 화면 → 폼 로그인
# → 인가 코드 → Gateway 콜백(세션 GW_SESSION) → 돌아갈 곳 → /api/me. 앞단(edge·Caddy)의 경로 나누기와 공개 주소 설정을 함께 본다.
#
#   BASE_URL=http://localhost:18080 bash scripts/smoke-bff-login.sh          (경로 방식: / 와 /auth/ 가 한 주소)
#   BASE_URL=http://localhost bash scripts/smoke-bff-login.sh                (호스트 방식: SAS 는 리다이렉트를 따라 auth 호스트로)
#
# 개발용 시드 사용자(auth-service db/dev-seed). 로컬 개발용 값이다
set -euo pipefail

BASE_URL=${BASE_URL:?BASE_URL 이 필요하다 (화면 주소, 예: http://localhost:18080)}
BASE_URL=${BASE_URL%/}
LOGIN_ID=${LOGIN_ID:-user1}
PASSWORD=${PASSWORD:-local-dev-pass}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar="$work/cookies"
c() { curl -s -c "$jar" -b "$jar" "$@"; }

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== 세션 없이 /api/me → 401"
code=$(c -o /dev/null -w '%{http_code}' "$BASE_URL/api/me")
[ "$code" = 401 ] || fail "/api/me → $code (기대 401)"

echo "== 로그인 시작 → SAS 로그인 화면"
signin=$(c -L -o /dev/null -w '%{url_effective}' "$BASE_URL/oauth2/authorization/auto?return_to=%2Forders")
case "$signin" in */signin*) ;; *) fail "로그인 화면으로 가지 않았다: $signin" ;; esac
echo "   $signin"
# 로그인 화면(인증 화면 SPA)이 하듯 CSRF 토큰을 GET csrf 본문으로. 주소는 로그인 화면 기준 상대 경로(경로 방식이면 /auth/csrf)
auth_base="${signin%%/signin*}"
csrf=$(c "$auth_base/csrf" | sed -nE 's/.*"token":"([^"]+)".*/\1/p')
[ -n "$csrf" ] || fail "CSRF 토큰을 받지 못했다 ($auth_base/csrf)"

echo "== 폼 로그인 → 인가 코드 → Gateway 콜백 → 돌아갈 곳"
# 폼 action 은 상대 경로(login): 로그인 화면 주소 기준 (경로 방식이면 /auth/login)
login_url="$auth_base/login"
final=$(c -L -o /dev/null -w '%{url_effective}' \
  --data-urlencode "username=$LOGIN_ID" --data-urlencode "password=$PASSWORD" --data-urlencode "_csrf=$csrf" \
  "$login_url")
[ "$final" = "$BASE_URL/orders" ] || fail "로그인 뒤 $final (기대 $BASE_URL/orders)"
echo "   $final"
grep -q 'GW_SESSION' "$jar" || fail "BFF 세션 쿠키(GW_SESSION)가 없다"

echo "== 세션으로 /api/me"
me=$(c "$BASE_URL/api/me")
case "$me" in *'"userId"'*) echo "   $me" ;; *) fail "/api/me → $me" ;; esac

echo "== 세션으로 API (Gateway 가 토큰을 붙여 서비스로)"
code=$(c -o /dev/null -w '%{http_code}' "$BASE_URL/api/points/me")
[ "$code" = 200 ] || fail "/api/points/me → $code (기대 200)"

echo "OK: BFF 로그인"
