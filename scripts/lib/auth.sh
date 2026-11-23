# 스크립트용 access token (ADR 0008). 시나리오 스크립트가 source 해서 쓴다.
#
#   source "$(dirname "$0")/lib/auth.sh"
#   curl "$ORDER_URL/orders/$id" -H "$(auth 1)"          # 사용자 1 로
#   curl -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" ...   # 관리자로
#
# 사용자는 auth-service 의 개발용 시드(db/dev-seed): id 1~4 = user1~user4, 100 = admin, 비밀번호 local-dev-pass.
# 토큰은 파일에 10분 캐시한다(access token 수명 15분). 매 요청마다 로그인하면 기기(refresh family)가 계속 늘어난다.
# auth-service 를 다시 띄우면(임시 서명 키) 캐시한 토큰이 무효가 되므로 지운다: rm -rf "$TOKEN_CACHE_DIR"

AUTH_URL=${AUTH_URL:-http://localhost:8086}
DEV_PASSWORD=${DEV_PASSWORD:-local-dev-pass}
TOKEN_CACHE_DIR=${TOKEN_CACHE_DIR:-${TMPDIR:-/tmp}/commerce-saga-tokens}

login_id_of() { # userId
  case $1 in
    100) echo admin ;;
    *) echo "user$1" ;;
  esac
}

access_token() { # userId
  local file="$TOKEN_CACHE_DIR/$1" token
  mkdir -p "$TOKEN_CACHE_DIR"
  if [ -s "$file" ] && [ $(( $(date +%s) - $(stat -c %Y "$file") )) -lt 600 ]; then
    cat "$file"
    return 0
  fi
  token=$(curl -sf -X POST "$AUTH_URL/auth/login" -H 'Content-Type: application/json' -H 'User-Agent: commerce-saga-scripts' \
    -d "{\"loginId\":\"$(login_id_of "$1")\",\"password\":\"$DEV_PASSWORD\"}" \
    | sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p')
  if [ -z "$token" ]; then
    echo "FAIL: 사용자 $1 로그인 실패 ($AUTH_URL)" >&2
    return 1
  fi
  printf '%s' "$token" > "$file"
  printf '%s' "$token"
}

# curl -H 에 그대로 넣는 Authorization 헤더
auth() { # userId
  printf 'Authorization: Bearer %s' "$(access_token "$1")"
}
