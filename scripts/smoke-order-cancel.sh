#!/usr/bin/env bash
# 시나리오 20. 주문 취소 (ADR 0020, docs/scenarios/20-order-cancel.md)
#
# 준비: 인프라와 네 서비스(inventory 8082, point 8083, payment 8084, order 8081)를 띄운다 (smoke-saga.sh 와 같음)
# 실행: scripts/smoke-order-cancel.sh
#
# 확인하는 것
#   20a. 완료된 주문 취소: 202 → 결제 취소(환불) → 포인트 환불 → 재고 반환 → CANCELLED.
#        결제는 REFUNDED, 포인트·재고(가용·예약)는 주문 전과 같다. 다시 취소하면 200 이고 아무것도 두 번 되돌리지 않는다
#   20b. 결제 중 취소: 결제 응답을 기다렸다가(PG 지연) 승인되면 결제 취소부터 보상 → CANCELLED(사용자 취소).
#        Saga 기록에 CANCEL_REQUESTED 가 결제 승인보다 앞서고, 결제는 REFUNDED, 포인트·재고는 그대로
#   20c. 남의 주문 취소 → 404, 주문은 그대로
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 USER1~USER4, 관리자 ADMIN (고정 UUID, lib/users.sh)
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID (PRODUCT_KEYBOARD 등)
source "$(dirname "$0")/lib/products.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-30}
# 결제 응답을 늦춰 "결제 중"에 취소한다. PG 클라이언트 타임아웃(3s)보다 짧아야 승인으로 끝난다
PG_DELAY_MILLIS=${PG_DELAY_MILLIS:-2000}
USER=$USER2
OTHER=$USER3
PRODUCT=$PRODUCT_MOUSE
PRICE=$(seed_price "$PRODUCT")

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
fail() { echo "FAIL: $*" >&2; exit 1; }
expect_eq() { # name expected actual
  [ "$2" = "$3" ] || fail "$1 expected '$2' but was '$3'"
  echo "  ok  $1 = $3"
}

stock() { curl -sf "$INVENTORY_URL/stocks/$PRODUCT"; }
points() { curl -sf "$POINT_URL/points/me" -H "$(auth "$USER")" | field balance; }
order_json() { curl -sf "$ORDER_URL/orders/$1" -H "$(auth "$USER")"; }
saga() { curl -sf "$ORDER_URL/orders/$1/saga" -H "$(auth "$USER")"; }
payment_status() { curl -sf "$PAYMENT_URL/payments/orders/$1" -H "$(auth "$USER")" | field status; }
place() { # quantity pointAmount → orderId
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth "$USER")" \
    -d "{\"lines\":[{\"productId\":\"$PRODUCT\",\"quantity\":$1,\"unitPrice\":$PRICE}],\"pointAmount\":$2}" \
    | field orderId
}
cancel() { # userId orderId → HTTP 상태
  curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDER_URL/orders/$2/cancel" -H "$(auth "$1")"
}
set_pg() { # mode delayMillis
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth "$ADMIN")" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":$2}" > /dev/null
}
wait_until() { # description command...
  local what=$1 deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  shift
  until "$@"; do
    [ "$(date +%s)" -le "$deadline" ] || fail "timeout waiting for $what"
    sleep 0.2
  done
}
status_is() { [ "$(order_json "$1" | field status)" = "$2" ]; }
saga_state_is() { [ "$(saga "$1" | field state)" = "$2" ]; }
payment_is() { [ "$(payment_status "$1")" = "$2" ]; }

trap 'set_pg APPROVE 0 || true' EXIT
set_pg APPROVE 0

echo "== 20a. 완료된 주문 취소 → 결제 환불 → 포인트 환불 → 재고 반환"
avail_before=$(stock | field available)
reserved_before=$(stock | field reserved)
points_before=$(points)
order_a=$(place 1 300)
wait_until "주문 COMPLETED" status_is "$order_a" COMPLETED
expect_eq "완료 뒤 포인트" "$((points_before - 300))" "$(points)"
expect_eq "완료 뒤 가용 재고" "$((avail_before - 1))" "$(stock | field available)"

expect_eq "취소 요청" 202 "$(cancel "$USER" "$order_a")"
wait_until "주문 CANCELLED" status_is "$order_a" CANCELLED
expect_eq "취소 사유" "사용자 취소" "$(order_json "$order_a" | field cancelReason)"
wait_until "결제 REFUNDED" payment_is "$order_a" REFUNDED
echo "  ok  결제 = REFUNDED"
expect_eq "포인트 원복" "$points_before" "$(points)"
expect_eq "가용 재고 원복" "$avail_before" "$(stock | field available)"
expect_eq "예약 재고 원복" "$reserved_before" "$(stock | field reserved)"
# 단계 순서: 완료 → 결제 취소 → 포인트 환불 → 재고 반환 → 취소
steps=$(saga "$order_a" | sed 's/.*"history"://' \
  | grep -oE '"state":"(COMPLETED|CANCELLING_PAYMENT|REFUNDING_POINT|RETURNING_STOCK|CANCELLED)"' \
  | sed -E 's/"state":"([A-Z_]+)"/\1/' | uniq | tr '\n' ' ')
expect_eq "Saga 단계" "COMPLETED CANCELLING_PAYMENT REFUNDING_POINT RETURNING_STOCK CANCELLED " "$steps"

expect_eq "다시 취소" 200 "$(cancel "$USER" "$order_a")"
sleep 2
expect_eq "다시 취소 뒤 포인트" "$points_before" "$(points)"
expect_eq "다시 취소 뒤 가용 재고" "$avail_before" "$(stock | field available)"

echo "== 20b. 결제 중 취소 → 결제 응답을 기다렸다가 승인이면 결제 취소부터 보상"
avail_before=$(stock | field available)
reserved_before=$(stock | field reserved)
points_before=$(points)
set_pg DELAY "$PG_DELAY_MILLIS"
order_b=$(place 1 200)
wait_until "Saga PAYING" saga_state_is "$order_b" PAYING
expect_eq "결제 중 취소 요청" 202 "$(cancel "$USER" "$order_b")"
expect_eq "요청 직후 주문 상태" PENDING "$(order_json "$order_b" | field status)"
set_pg APPROVE 0
wait_until "주문 CANCELLED" status_is "$order_b" CANCELLED
expect_eq "취소 사유" "사용자 취소" "$(order_json "$order_b" | field cancelReason)"
events=$(saga "$order_b" | grep -oE '"event":"[A-Z_]+"' | sed -E 's/"event":"([A-Z_]+)"/\1/' | tr '\n' ' ')
case "$events" in
  *CANCEL_REQUESTED*PAYMENT_APPROVED*PAYMENT_CANCELLED*POINT_REFUNDED*STOCK_RELEASED*) echo "  ok  Saga 기록 = $events" ;;
  *) fail "Saga 기록 순서가 다름: $events" ;;
esac
wait_until "결제 REFUNDED" payment_is "$order_b" REFUNDED
echo "  ok  결제 = REFUNDED"
expect_eq "포인트 원복" "$points_before" "$(points)"
expect_eq "가용 재고 원복" "$avail_before" "$(stock | field available)"
expect_eq "예약 재고 원복" "$reserved_before" "$(stock | field reserved)"

echo "== 20c. 남의 주문 취소 → 404"
order_c=$(place 1 0)
wait_until "주문 COMPLETED" status_is "$order_c" COMPLETED
expect_eq "남의 주문 취소" 404 "$(cancel "$OTHER" "$order_c")"
expect_eq "주문 상태 그대로" COMPLETED "$(order_json "$order_c" | field status)"
expect_eq "취소 요청 없음" null "$(order_json "$order_c" | field cancelRequestedAt)"
# 뒷정리: 이 주문도 취소해 재고·결제를 되돌린다
expect_eq "본인 취소" 202 "$(cancel "$USER" "$order_c")"
wait_until "주문 CANCELLED" status_is "$order_c" CANCELLED

echo "PASS: 주문 취소 시나리오 20a·20b·20c"
