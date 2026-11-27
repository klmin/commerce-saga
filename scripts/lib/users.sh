# 시드 사용자 id. 시나리오 스크립트가 (lib/auth.sh 를 거쳐) source 해서 쓴다.
#
#   source "$(dirname "$0")/lib/auth.sh"          # users.sh 도 함께 읽는다
#   USER_ID=$(user_id "${USER_ID:-3}")            # 시드 번호(1~4, 100 = admin)로 줘도, UUID 로 줘도 된다
#   curl ... -H "$(auth "$USER1")"                # 사용자 1 로
#   curl ... -H "$(auth "$ADMIN")"                # 관리자로
#
# 사용자 id 는 UUID(v7, ADR 0018). 시드 사용자는 서비스 사이(auth dev-seed·point 시드)에 맞춰 고정 UUID 를 쓴다.
# 로그인 아이디는 user1~user4·admin, 비밀번호 local-dev-pass (auth-service db/dev-seed, 로컬 개발용 값)

USER1=01900000-0000-7000-8001-000000000001   # user1 10,000P
USER2=01900000-0000-7000-8001-000000000002   # user2 10,000P
USER3=01900000-0000-7000-8001-000000000003   # user3 10,000P
USER4=01900000-0000-7000-8001-000000000004   # user4 0P
ADMIN=01900000-0000-7000-8001-000000000100   # admin (ROLE_ADMIN)

# 번호 n → 01900000-0000-7000-8001-<n 을 12자리로>. 1~4·100 은 시드 사용자의 고정 UUID 와 같다.
# 이미 UUID(숫자만이 아닌 값)면 그대로
user_id() { # n | uuid
  case $1 in
    ''|*[!0-9]*) echo "$1" ;;
    *) printf '01900000-0000-7000-8001-%012d\n' "$((10#$1))" ;;
  esac
}

# 출력용 짧은 이름: 시드 사용자면 user1~user4·admin, 아니면 UUID 그대로
user_label() { # uuid
  case $1 in
    "$ADMIN") echo admin ;;
    "$USER1") echo user1 ;;
    "$USER2") echo user2 ;;
    "$USER3") echo user3 ;;
    "$USER4") echo user4 ;;
    *) echo "$1" ;;
  esac
}

# 시드 사용자의 로그인 아이디 (시드가 아니면 실패: 비밀번호를 모른다)
login_id_of() { # n | uuid
  local id
  id=$(user_id "$1")
  case $id in
    "$ADMIN"|"$USER1"|"$USER2"|"$USER3"|"$USER4") user_label "$id" ;;
    *) echo "FAIL: 시드 사용자가 아니다 ($1)" >&2; return 1 ;;
  esac
}
