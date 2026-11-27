# 시드 상품 id·가격. 시나리오 스크립트가 source 해서 쓴다.
#
#   source "$(dirname "$0")/lib/products.sh"
#   PRODUCT_ID=$(product_id "${PRODUCT_ID:-2}")   # 시드 번호(1~5)로 줘도, UUID 로 줘도 된다
#   UNIT_PRICE=${UNIT_PRICE:-$(seed_price "$PRODUCT_ID")}
#
# 상품 id 는 UUID(v7, ADR 0018). 시드 상품 5개는 서비스 사이(product V2·inventory V2)에 맞춰 고정 UUID 를 쓴다.
# 가격은 product-service 시드(V2) 값. 서버 가격과 다르면 주문이 409 (ADR 0016)

PRODUCT_KEYBOARD=01900000-0000-7000-8000-000000000001    # 1 무선 키보드 39000
PRODUCT_MOUSE=01900000-0000-7000-8000-000000000002       # 2 무선 마우스 25000
PRODUCT_HUB=01900000-0000-7000-8000-000000000003         # 3 USB-C 허브 18000
PRODUCT_KEYCAP=01900000-0000-7000-8000-000000000004      # 4 한정판 키캡 12000 (재고 1)
PRODUCT_MONITOR_ARM=01900000-0000-7000-8000-000000000005 # 5 단종 모니터암 45000 (재고 0)

# 번호 n → 01900000-0000-7000-8000-<n 을 12자리로>. 1~5 는 시드 상품의 고정 UUID 와 같다.
# 시드가 아닌 번호(측정용 9001 등)도 같은 모양의 고정 UUID 가 된다. 이미 UUID(숫자만이 아닌 값)면 그대로
product_id() { # n | uuid
  case $1 in
    ''|*[!0-9]*) echo "$1" ;;
    *) printf '01900000-0000-7000-8000-%012d\n' "$((10#$1))" ;;
  esac
}

# 출력용 짧은 이름: product_id 가 만든 모양이면 번호(1~5 = 시드, 9001 …), 아니면 UUID 그대로
product_label() { # uuid
  local n
  case $1 in
    01900000-0000-7000-8000-*)
      n=${1#01900000-0000-7000-8000-}
      case $n in
        *[!0-9]*) echo "$1" ;;
        *) echo "$((10#$n))" ;;
      esac ;;
    *) echo "$1" ;;
  esac
}

# 시드 상품의 가격 (시드가 아니면 빈 값)
seed_price() { # n | uuid
  case $(product_id "$1") in
    "$PRODUCT_KEYBOARD") echo 39000 ;;
    "$PRODUCT_MOUSE") echo 25000 ;;
    "$PRODUCT_HUB") echo 18000 ;;
    "$PRODUCT_KEYCAP") echo 12000 ;;
    "$PRODUCT_MONITOR_ARM") echo 45000 ;;
  esac
}
