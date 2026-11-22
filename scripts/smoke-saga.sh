#!/usr/bin/env bash
# 1단계 기본 흐름 스모크 테스트 (docs/scenarios/00-basic-flow.md)
#
# 준비: 인프라와 두 서비스를 띄운다
#   docker compose -f infra/docker-compose.yml up -d --wait
#   ./gradlew :services:order-service:bootRun       (8081)
#   ./gradlew :services:inventory-service:bootRun   (8082)
# 실행:
#   scripts/smoke-basic-flow.sh
#
# 확인하는 것
#   1. 재고 있는 상품 주문 -> CONFIRMED, 재고 available 감소 / reserved 증가
#   2. 품절 상품(5) 포함 주문 -> REJECTED, 함께 주문한 상품 재고도 그대로
#   3. 다른 사용자의 주문 조회 -> 404
set -euo pipefail

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-15}

# jq 없이 평평한 JSON 에서 값 하나를 꺼낸다 (문자열·숫자)
field() {
  sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p"
}

now_ms() {
  date +%s%3N
}

stock() {
  curl -sf "$INVENTORY_URL/stocks/$1"
}

place_order() { # userId body
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "X-User-Id: $1" -d "$2"
}

get_order() { # userId orderId
  curl -sf "$ORDER_URL/orders/$2" -H "X-User-Id: $1"
}

wait_status() { # userId orderId expected
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS )) status=""
  while [ "$(date +%s)" -le "$deadline" ]; do
    status=$(get_order "$1" "$2" | field status)
    if [ "$status" = "$3" ]; then
      return 0
    fi
    sleep 0.1
  done
  echo "FAIL: order $2 expected $3 but was $status" >&2
  return 1
}

expect_eq() { # name expected actual
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 expected $2 but was $3" >&2
    exit 1
  fi
  echo "  ok  $1 = $3"
}

echo "== 1. 재고 있는 상품 주문 -> CONFIRMED"
before=$(stock 1)
avail_before=$(echo "$before" | field available)
reserved_before=$(echo "$before" | field reserved)
start=$(now_ms)
order=$(place_order 1 '{"lines":[{"productId":1,"quantity":2,"unitPrice":1000}]}')
order_id=$(echo "$order" | field orderId)
expect_eq "생성 직후 status" PENDING "$(echo "$order" | field status)"
wait_status 1 "$order_id" CONFIRMED
elapsed=$(( $(now_ms) - start ))
echo "  ok  orderId=$order_id CONFIRMED (${elapsed}ms)"
after=$(stock 1)
expect_eq "상품 1 available" $(( avail_before - 2 )) "$(echo "$after" | field available)"
expect_eq "상품 1 reserved" $(( reserved_before + 2 )) "$(echo "$after" | field reserved)"

echo "== 2. 품절 상품 포함 주문 -> REJECTED, 아무 재고도 잡지 않음"
avail2_before=$(stock 2 | field available)
order=$(place_order 1 '{"lines":[{"productId":2,"quantity":1,"unitPrice":500},{"productId":5,"quantity":1,"unitPrice":500}]}')
rejected_id=$(echo "$order" | field orderId)
wait_status 1 "$rejected_id" REJECTED
expect_eq "rejectReason" "insufficient stock: productId=5" "$(get_order 1 "$rejected_id" | field rejectReason)"
expect_eq "상품 2 available" "$avail2_before" "$(stock 2 | field available)"

echo "== 3. 다른 사용자의 주문 조회 -> 404"
code=$(curl -s -o /dev/null -w '%{http_code}' "$ORDER_URL/orders/$order_id" -H 'X-User-Id: 2')
expect_eq "user 2 -> user 1 주문" 404 "$code"

echo "PASS (주문 생성 -> CONFIRMED ${elapsed}ms)"
