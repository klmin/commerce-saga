#!/usr/bin/env bash
# 시나리오 14. 주문 가격은 서버가 정한다: 화면 가격과 다르면 409 → 확인 후 새 가격으로 주문 (docs/scenarios/14-price-change.md, ADR 0016)
#
# 준비: 인프라, 서비스 전부(product-service 8087 포함), gateway(8000)
# 실행: scripts/smoke-price-change.sh
#
# 확인하는 것
#   14a. 관리자가 상품 3 의 가격을 바꾸면 가격 변경 이력에 이전·새 가격과 바꾼 관리자가 남는다
#   14b. 옛 가격(화면에서 본 가격)으로 주문 → 409 + 지금 가격, 주문은 만들어지지 않는다
#   14c. 같은 Idempotency-Key 로 새 가격을 보내면 주문된다 (409 는 키를 남기지 않음). 주문에는 그 가격이 스냅샷된다
#   14d. 판매 중지 상품 주문 → 422
#   14e. 장바구니: 가격 변경 전에 담은 상품은 "담을 때 가격 → 지금 가격"으로 보이고, 장바구니 주문(fromCart)은 주문한 줄을 뺀다
# 끝나면 가격·판매 상태를 원래대로 돌려놓는다
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
PRODUCT_ID=${PRODUCT_ID:-3}
ADMIN=100
USER_ID=1

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
admin_put() { # path json
  curl -sf -X PUT "$GATEWAY_URL/api/admin/products/$PRODUCT_ID$1" -H "$(auth $ADMIN)" \
    -H 'Content-Type: application/json' -d "$2" > /dev/null
}
order() { # key unitPrice → "status body"
  local out
  out=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/api/orders" -H "$(auth $USER_ID)" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":$2}],\"pointAmount\":0}")
  echo "$(tail -n1 <<< "$out") $(head -n -1 <<< "$out")"
}

original=$(curl -sf "$GATEWAY_URL/api/products/$PRODUCT_ID" | field price)
[ -n "$original" ] || fail "상품 $PRODUCT_ID 조회 실패 (product-service·Gateway 확인)"
changed=$(( original + 1000 ))
trap 'admin_put /price "{\"price\":$original}" || true; admin_put /sale "{\"onSale\":true}" || true; curl -s -o /dev/null -X DELETE "$GATEWAY_URL/api/cart/items/$PRODUCT_ID" -H "$(auth $USER_ID)" || true' EXIT

# 14e 준비: 가격을 바꾸기 전에 장바구니에 담는다 (담을 때 가격 = 원래 가격)
curl -sf -X PUT "$GATEWAY_URL/api/cart/items/$PRODUCT_ID" -H "$(auth $USER_ID)" -H 'Content-Type: application/json' \
  -d '{"quantity":1}' > /dev/null || fail "장바구니 담기 실패"

echo "== 14a. 관리자가 가격 변경 → 이력"
admin_put /price "{\"price\":$changed}"
latest=$(curl -sf "$GATEWAY_URL/api/admin/products/$PRODUCT_ID/price-history" -H "$(auth $ADMIN)")
[ "$(field oldPrice <<< "$latest")" = "$original" ] && [ "$(field newPrice <<< "$latest")" = "$changed" ] \
  && [ "$(field changedBy <<< "$latest")" = "$ADMIN" ] || fail "가격 이력이 다름: $latest"
ok "이력 $original → $changed (관리자 $ADMIN)"

echo "== 14b. 옛 가격으로 주문 → 409 + 지금 가격"
key="price-change-$(date +%s%N)"
read -r code body <<< "$(order "$key" "$original")"
[ "$code" = 409 ] || fail "옛 가격 주문 → $code (기대 409): $body"
grep -q "\"price\":$changed" <<< "$body" || fail "409 응답에 지금 가격이 없음: $body"
ok "409, currentPrices 에 $changed"

echo "== 14c. 같은 키로 새 가격 → 주문, 가격 스냅샷"
read -r code body <<< "$(order "$key" "$changed")"
[ "$code" = 201 ] || fail "새 가격 주문 → $code (기대 201): $body"
order_id=$(field orderId <<< "$body")
[ "$(field unitPrice <<< "$body")" = "$changed" ] || fail "주문 줄 가격이 $changed 이 아님: $body"
ok "주문 …${order_id: -8} 생성, 줄 가격 $changed"
read -r code body <<< "$(order "$key" "$changed")"
[ "$code" = 200 ] && [ "$(field orderId <<< "$body")" = "$order_id" ] || fail "같은 키 재요청 → $code $body"
ok "같은 키 재요청은 같은 주문 (200)"

echo "== 14e. 장바구니: 가격 변경 표시, 장바구니 주문은 주문한 줄을 뺀다"
cart=$(curl -sf "$GATEWAY_URL/api/cart" -H "$(auth $USER_ID)")
grep -q "\"productId\":$PRODUCT_ID,[^}]*\"addedPrice\":$original,\"currentPrice\":$changed,[^}]*\"priceChanged\":true" <<< "$cart" \
  || fail "장바구니에 가격 변경이 보이지 않음: $cart"
ok "장바구니 담을 때 $original → 지금 $changed"
out=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY_URL/api/orders" -H "$(auth $USER_ID)" -H 'Content-Type: application/json' \
  -d "{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":$changed}],\"pointAmount\":0,\"fromCart\":true}")
[ "$(tail -n1 <<< "$out")" = 201 ] || fail "장바구니 주문 → $(tail -n1 <<< "$out")"
cart=$(curl -sf "$GATEWAY_URL/api/cart" -H "$(auth $USER_ID)")
grep -q "\"productId\":$PRODUCT_ID," <<< "$cart" && fail "주문한 상품이 장바구니에 남음: $cart"
ok "장바구니 주문 201, 주문한 줄이 빠짐"

echo "== 14d. 판매 중지 상품 → 422"
admin_put /sale '{"onSale":false}'
read -r code body <<< "$(order "stopped-$(date +%s%N)" "$changed")"
[ "$code" = 422 ] || fail "판매 중지 상품 주문 → $code (기대 422): $body"
ok "422"

echo "PASS"
