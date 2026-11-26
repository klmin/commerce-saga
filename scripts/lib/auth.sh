# 스크립트용 access token. 시나리오 스크립트가 source 해서 쓴다.
#
#   source "$(dirname "$0")/lib/auth.sh"
#   curl "$ORDER_URL/orders/$id" -H "$(auth 1)"          # 사용자 1 로
#   curl -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" ...   # 관리자로
#
# SAS(auth-service)의 스크립트용 공개 클라이언트(scripts: 시크릿 없음, PKCE, 루프백 redirect, 후속 5-3c)로 인가 코드 흐름을
# curl 로 돈다: 인가 요청 → (로그인 화면 대신) GET csrf → 폼 로그인 → 인가 코드(redirect 주소에서 읽음) → 토큰 교환.
# 브라우저 앱(Gateway BFF)과 같은 SAS 로그인이고 같은 서명 키·issuer 의 토큰이다.
#
# 사용자는 auth-service 의 개발용 시드(db/dev-seed): id 1~4 = user1~user4, 100 = admin, 비밀번호 local-dev-pass.
# 토큰은 파일에 10분 캐시한다(access token 수명 15분). auth-service 를 다시 띄워 서명 키가 바뀌었으면 지운다: rm -rf "$TOKEN_CACHE_DIR"

AUTH_URL=${AUTH_URL:-http://localhost:8086}
DEV_PASSWORD=${DEV_PASSWORD:-local-dev-pass}
TOKEN_CACHE_DIR=${TOKEN_CACHE_DIR:-${TMPDIR:-/tmp}/commerce-saga-tokens}
SCRIPTS_CLIENT_ID=${SCRIPTS_CLIENT_ID:-scripts}
SCRIPTS_REDIRECT_URI=${SCRIPTS_REDIRECT_URI:-http://127.0.0.1/scripts/callback}

login_id_of() { # userId
  case $1 in
    100) echo admin ;;
    *) echo "user$1" ;;
  esac
}

# base64url (패딩 없음)
_b64url() { base64 | tr '+/' '-_' | tr -d '=\n'; }

# URL 의 쿼리 값 하나 (디코드하지 않음: 코드·state 는 URL 에 그대로 쓸 수 있는 글자다)
_query_param() { # url name
  printf '%s' "$1" | sed -n "s/.*[?&]$2=\([^&]*\).*/\1/p"
}

# SAS 세션의 CSRF 토큰 (GET csrf 본문, 인증 화면 SPA 와 같은 방법. 세션은 쿠키 파일에)
_csrf_of() { # jar authBaseUrl
  curl -s -c "$1" -b "$1" "$2/csrf" | sed -nE 's/.*"token":"([^"]+)".*/\1/p'
}

# 인가 코드 흐름 한 번 → access token
user_token() { # loginId [password]
  local password=${2:-$DEV_PASSWORD} jar verifier challenge state authorize signin csrf saved callback code token
  jar=$(mktemp)
  verifier=$(head -c 32 /dev/urandom | _b64url)
  challenge=$(printf '%s' "$verifier" | openssl dgst -binary -sha256 | _b64url)
  state=$(head -c 12 /dev/urandom | _b64url)
  authorize="$AUTH_URL/oauth2/authorize?response_type=code&client_id=$SCRIPTS_CLIENT_ID&scope=openid&state=$state"
  authorize="$authorize&redirect_uri=$SCRIPTS_REDIRECT_URI&code_challenge=$challenge&code_challenge_method=S256"

  # 로그인 안 된 브라우저 → 로그인 화면 주소(인증 화면 SPA, 열지 않는다). 그 기준 주소의 csrf 로 토큰을 받는다
  signin=$(curl -s -c "$jar" -b "$jar" -H 'Accept: text/html' -o /dev/null -w '%{redirect_url}' "$authorize")
  csrf=$(_csrf_of "$jar" "${signin%%/signin*}")
  if [ -z "$csrf" ]; then
    echo "FAIL: 로그인 화면을 열지 못했다 ($AUTH_URL, 인가 요청 → '$signin')" >&2
    rm -f "$jar"; return 1
  fi
  # 폼 로그인 → 저장된 인가 요청 → redirect 주소(?code=). redirect 주소로는 가지 않는다(루프백, 받는 곳 없음)
  saved=$(curl -s -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode "username=$1" --data-urlencode "password=$password" --data-urlencode "_csrf=$csrf" \
    "${signin%%/signin*}/login")
  case "$saved" in
    *signin\?error*) echo "FAIL: $1 로그인 실패 (아이디·비밀번호 또는 잠김)" >&2; rm -f "$jar"; return 1 ;;
  esac
  callback=$(curl -s -c "$jar" -b "$jar" -H 'Accept: text/html' -o /dev/null -w '%{redirect_url}' "$saved")
  rm -f "$jar"
  code=$(_query_param "$callback" code)
  if [ -z "$code" ] || [ "$(_query_param "$callback" state)" != "$state" ]; then
    echo "FAIL: 인가 코드를 받지 못했다 ('$callback')" >&2
    return 1
  fi
  token=$(curl -s -X POST "$AUTH_URL/oauth2/token" \
    -d grant_type=authorization_code -d "client_id=$SCRIPTS_CLIENT_ID" -d "code=$code" \
    --data-urlencode "redirect_uri=$SCRIPTS_REDIRECT_URI" -d "code_verifier=$verifier" \
    | sed -nE 's/.*"access_token":"([^"]+)".*/\1/p')
  if [ -z "$token" ]; then
    echo "FAIL: 토큰 교환 실패 ($AUTH_URL/oauth2/token)" >&2
    return 1
  fi
  printf '%s' "$token"
}

# 가입 폼(인증 화면 /register 가 보내는 POST /signup). 성공이면 0, 같은 아이디가 있으면 2
signup_user() { # loginId password
  local jar csrf location
  jar=$(mktemp)
  csrf=$(_csrf_of "$jar" "$AUTH_URL")
  location=$(curl -s -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode "loginId=$1" --data-urlencode "password=$2" --data-urlencode "_csrf=$csrf" "$AUTH_URL/signup")
  rm -f "$jar"
  case "$location" in
    *register\?error=duplicate*) return 2 ;;
    *register\?error*|"") echo "FAIL: 가입 실패 ($1 → '$location')" >&2; return 1 ;;
  esac
}

# access token 의 sub (userId)
token_sub() { # token
  local payload
  payload=$(printf '%s' "$1" | cut -d. -f2 | tr '_-' '/+')
  while [ $(( ${#payload} % 4 )) -ne 0 ]; do payload="$payload="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | sed -nE 's/.*"sub":"([^"]+)".*/\1/p'
}

access_token() { # userId
  local file="$TOKEN_CACHE_DIR/$1" token
  mkdir -p "$TOKEN_CACHE_DIR"
  if [ -s "$file" ] && [ $(( $(date +%s) - $(stat -c %Y "$file") )) -lt 600 ]; then
    cat "$file"
    return 0
  fi
  token=$(user_token "$(login_id_of "$1")") || return 1
  printf '%s' "$token" > "$file"
  printf '%s' "$token"
}

# curl -H 에 그대로 넣는 Authorization 헤더
auth() { # userId
  printf 'Authorization: Bearer %s' "$(access_token "$1")"
}
