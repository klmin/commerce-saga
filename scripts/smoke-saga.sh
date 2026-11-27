#!/usr/bin/env bash
# 주문 Saga 스모크 테스트 (docs/scenarios/00-basic-flow.md, 01-payment-failure-compensation.md)
#
# 준비: 인프라와 네 서비스를 띄운다
#   docker compose -f infra/docker-compose.yml up -d --wait
#   ./gradlew :services:inventory-service:bootRun   (8082)
#   ./gradlew :services:point-service:bootRun       (8083)
#   ./gradlew :services:payment-service:bootRun     (8084)
#   ./gradlew :services:order-service:bootRun       (8081)
# 실행:
#   scripts/smoke-saga.sh
#
# 확인하는 것
#   1. 정상 주문(포인트 일부 사용) -> COMPLETED. 포인트 차감, 결제 승인, 재고 판매 확정(reserved 원복)
#   2. 품절 상품 포함 주문 -> CANCELLED. 함께 주문한 상품 재고도 그대로
#   3. 결제 실패 주입 -> 포인트 환불 -> 재고 해제 -> CANCELLED (보상 순서 검증)
#   4. 다른 사용자의 주문·결제 조회 -> 404
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 USER1~USER4, 관리자 ADMIN (고정 UUID, lib/users.sh)
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID (PRODUCT_KEYBOARD 등)
source "$(dirname "$0")/lib/products.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-20}

# jq 없이 평평한 JSON 에서 첫 번째 값 하나를 꺼낸다 (문자열·숫자·불리언)
field() {
  sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1
}

now_ms() {
  date +%s%3N
}

stock() { curl -sf "$INVENTORY_URL/stocks/$1"; }
points() { curl -sf "$POINT_URL/points/me" -H "$(auth "$1")" | field balance; }
saga() { curl -sf "$ORDER_URL/orders/$2/saga" -H "$(auth "$1")"; }  # userId orderId

place_order() { # userId body
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth "$1")" -d "$2"
}

get_order() { # userId orderId
  curl -sf "$ORDER_URL/orders/$2" -H "$(auth "$1")"
}

set_pg() { # mode
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth "$ADMIN")" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":0}" > /dev/null
}

wait_until() { # description command...
  local what=$1 deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  shift
  while [ "$(date +%s)" -le "$deadline" ]; do
    if "$@"; then
      return 0
    fi
    sleep 0.1
  done
  echo "FAIL: timeout waiting for $what" >&2
  return 1
}

status_is() { [ "$(get_order "$1" "$2" | field status)" = "$3" ]; }
reserved_is() { [ "$(stock "$1" | field reserved)" = "$2" ]; }

expect_eq() { # name expected actual
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 expected '$2' but was '$3'" >&2
    exit 1
  fi
  echo "  ok  $1 = $3"
}

# 실패해도 결제 장애 주입을 원래대로 돌려놓는다
trap 'set_pg APPROVE || true' EXIT
set_pg APPROVE

echo "== 1. 정상 주문 (포인트 500 사용) -> COMPLETED"
avail_before=$(stock "$PRODUCT_KEYBOARD" | field available)
reserved_before=$(stock "$PRODUCT_KEYBOARD" | field reserved)
points_before=$(points "$USER1")
start=$(now_ms)
order=$(place_order "$USER1" "{\"lines\":[{\"productId\":\"$PRODUCT_KEYBOARD\",\"quantity\":2,\"unitPrice\":39000}],\"pointAmount\":500}")
order_id=$(echo "$order" | field orderId)
expect_eq "생성 직후 status" PENDING "$(echo "$order" | field status)"
wait_until "order COMPLETED" status_is 1 "$order_id" COMPLETED
elapsed=$(( $(now_ms) - start ))
echo "  ok  orderId=$order_id COMPLETED (${elapsed}ms)"
expect_eq "결제 금액" 77500 "$(get_order "$USER1" "$order_id" | field paidAmount)"
expect_eq "포인트 잔액" $(( points_before - 500 )) "$(points "$USER1")"
expect_eq "결제 상태" APPROVED "$(curl -sf "$PAYMENT_URL/payments/orders/$order_id" -H "$(auth "$USER1")" | field status)"
# OrderCompleted 이벤트(코레오그래피)로 예약 재고가 판매 확정되어 reserved 가 원래대로
wait_until "stock confirmed" reserved_is "$PRODUCT_KEYBOARD" "$reserved_before"
expect_eq "상품 1 available" $(( avail_before - 2 )) "$(stock "$PRODUCT_KEYBOARD" | field available)"
expect_eq "Saga 단계" COMPLETED "$(saga "$USER1" "$order_id" | field state)"

echo "== 2. 품절 상품 포함 주문 -> CANCELLED, 아무 재고도 잡지 않음"
avail2_before=$(stock "$PRODUCT_MOUSE" | field available)
order=$(place_order "$USER1" "{\"lines\":[{\"productId\":\"$PRODUCT_MOUSE\",\"quantity\":1,\"unitPrice\":25000},{\"productId\":\"$PRODUCT_MONITOR_ARM\",\"quantity\":1,\"unitPrice\":45000}]}")
oos_id=$(echo "$order" | field orderId)
wait_until "order CANCELLED" status_is 1 "$oos_id" CANCELLED
expect_eq "cancelReason" "insufficient stock: productId=$PRODUCT_MONITOR_ARM" "$(get_order "$USER1" "$oos_id" | field cancelReason)"
expect_eq "상품 2 available" "$avail2_before" "$(stock "$PRODUCT_MOUSE" | field available)"

echo "== 3. 결제 실패 -> 포인트 환불 -> 재고 해제 -> CANCELLED"
set_pg DECLINE
avail3_before=$(stock "$PRODUCT_HUB" | field available)
reserved3_before=$(stock "$PRODUCT_HUB" | field reserved)
points3_before=$(points "$USER2")
order=$(place_order "$USER2" "{\"lines\":[{\"productId\":\"$PRODUCT_HUB\",\"quantity\":1,\"unitPrice\":18000}],\"pointAmount\":300}")
fail_id=$(echo "$order" | field orderId)
wait_until "order CANCELLED" status_is 2 "$fail_id" CANCELLED
set_pg APPROVE
expect_eq "cancelReason" "declined by PG (fault injection)" "$(get_order "$USER2" "$fail_id" | field cancelReason)"
expect_eq "포인트 잔액(환불)" "$points3_before" "$(points "$USER2")"
expect_eq "상품 3 available(해제)" "$avail3_before" "$(stock "$PRODUCT_HUB" | field available)"
expect_eq "상품 3 reserved(해제)" "$reserved3_before" "$(stock "$PRODUCT_HUB" | field reserved)"
history=$(saga "$USER2" "$fail_id")
# 보상 순서: PAYMENT_FAILED -> REFUNDING_POINT, POINT_REFUNDED -> RELEASING_STOCK, STOCK_RELEASED -> CANCELLED
order_of_steps=$(echo "$history" | sed 's/.*"history"://' | grep -oE '"state":"(PAYING|REFUNDING_POINT|RELEASING_STOCK|CANCELLED)"' | tr -d '"' | sed 's/state://' | tr '\n' ' ')
expect_eq "Saga 보상 순서" "PAYING REFUNDING_POINT RELEASING_STOCK CANCELLED " "$order_of_steps"

echo "== 4. 다른 사용자의 주문·Saga·결제 조회 -> 404"
expect_eq "user 2 -> user 1 주문" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$ORDER_URL/orders/$order_id" -H "$(auth "$USER2")")"
expect_eq "user 2 -> user 1 Saga" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$ORDER_URL/orders/$order_id/saga" -H "$(auth "$USER2")")"
expect_eq "user 2 -> user 1 결제" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$PAYMENT_URL/payments/orders/$order_id" -H "$(auth "$USER2")")"

echo "PASS (정상 주문 생성 -> COMPLETED ${elapsed}ms)"
