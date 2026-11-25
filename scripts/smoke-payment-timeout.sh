#!/usr/bin/env bash
# 시나리오 2. 결제 타임아웃 → PG 조회로 확정 / 오래 걸리면 결제 취소 + 늦은 승인 자동 환불 (docs/scenarios/02-payment-timeout.md)
#
# 준비: 인프라와 네 서비스를 띄운다. 타임아웃을 짧게 둔다 (CI e2e 와 같은 값)
#   order-service:   SAGA_STEP_TIMEOUT=10s SAGA_PAYMENT_MAX_RESENDS=2 SAGA_TIMEOUT_SCAN_INTERVAL=1s
#   payment-service: PG_CLIENT_TIMEOUT=3s(기본) PAYMENT_RECONCILE_INTERVAL=1s
# 실행: scripts/smoke-payment-timeout.sh
#
# 확인하는 것
#   2a. PG 가 클라이언트 타임아웃(3초)보다 늦게(6초) 승인: 결제는 UNKNOWN 으로 기록되고 실패 응답이 나가지 않는다.
#       PG 조회로 승인이 확인되면 그때 응답해 주문이 COMPLETED. 바로 보상했다면 돈은 나갔는데 주문이 취소됐을 것
#   2b. PG 가 Saga 의 결제 대기 한도(10초 × 재전송 2번 + 10초 = 약 30초)보다 늦게(45초) 승인:
#       Saga 가 결제를 포기하고 CancelPayment → 포인트 환불 → 재고 해제 → CANCELLED.
#       그 뒤 PG 가 승인하면 결제 서비스가 PG 조회로 알아채고 승인 응답 없이 PG 환불한다 (REFUNDED, 원장 refunded=true)
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"
export MSYS_NO_PATHCONV=1

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
USER_ID=${USER_ID:-1}
PRODUCT_ID=${PRODUCT_ID:-1}
# 상품 시드 가격 (product-service V2). 화면 가격(unitPrice)이 서버 가격과 다르면 주문이 409 (ADR 0016)
UNIT_PRICE=${UNIT_PRICE:-$(case $PRODUCT_ID in 1) echo 39000;; 2) echo 25000;; 3) echo 18000;; 4) echo 12000;; *) echo 45000;; esac)}
POINT_AMOUNT=${POINT_AMOUNT:-100}
SHORT_DELAY_MILLIS=${SHORT_DELAY_MILLIS:-6000}
LONG_DELAY_MILLIS=${LONG_DELAY_MILLIS:-45000}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-90}

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
stock() { curl -sf "$INVENTORY_URL/stocks/$1"; }
points() { curl -sf "$POINT_URL/points/me" -H "$(auth $1)" | field balance; }
order_json() { curl -sf "$ORDER_URL/orders/$1" -H "$(auth $USER_ID)"; }
saga_json() { curl -sf "$ORDER_URL/orders/$1/saga" -H "$(auth $USER_ID)"; }
payment_status() { curl -s "$PAYMENT_URL/payments/orders/$1" -H "$(auth $USER_ID)" | field status; }
psql_q() { # db sql
  docker compose -f "$COMPOSE_FILE" exec -T -e PGPASSWORD=postgres postgres psql -U postgres -d "$1" -tAc "$2"
}

expect_eq() {
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 expected '$2' but was '$3'" >&2
    exit 1
  fi
  echo "  ok  $1 = $3"
}

wait_until() { # description timeout-seconds command...
  local what=$1 deadline=$(( $(date +%s) + $2 ))
  shift 2
  while [ "$(date +%s)" -le "$deadline" ]; do
    if "$@"; then return 0; fi
    sleep 0.5
  done
  echo "FAIL: timeout waiting for $what" >&2
  return 1
}

fault() { # mode delayMillis
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":$2}" > /dev/null
}
# 실패해도 PG 는 정상 승인으로 돌려 둔다 (뒤 단계가 쓴다)
trap 'fault APPROVE 0 || true' EXIT

place_order() {
  local body="{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":$UNIT_PRICE}],\"pointAmount\":$POINT_AMOUNT}"
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" -d "$body" \
    | field orderId
}

payment_is() { [ "$(payment_status "$1")" = "$2" ]; }
order_status_is() { [ "$(order_json "$1" | field status)" = "$2" ]; }
stock_reserved_is() { [ "$(stock "$PRODUCT_ID" | field reserved)" = "$1" ]; }
ledger() { psql_q payment_db "select approved || ',' || refunded from fake_pg_transactions where order_id = '$1'"; }

echo "== 2a. PG 응답이 클라이언트 타임아웃보다 늦음 → UNKNOWN → PG 조회로 승인 확정 → COMPLETED"
points_before=$(points "$USER_ID")
avail_before=$(stock "$PRODUCT_ID" | field available)
reserved_before=$(stock "$PRODUCT_ID" | field reserved)

fault DELAY "$SHORT_DELAY_MILLIS"
id=$(place_order)
wait_until "결제 UNKNOWN" 30 payment_is "$id" UNKNOWN
fault APPROVE 0   # 이미 PG 에서 처리 중인 요청은 지연 그대로 진행된다
echo "  ok  PG 타임아웃 → 결제 UNKNOWN (실패로 응답하지 않음)"
expect_eq "결과 모르는 동안 주문 상태" PENDING "$(order_json "$id" | field status)"
expect_eq "결과 모르는 동안 Saga 단계" PAYING "$(saga_json "$id" | field state)"

wait_until "주문 COMPLETED" "$TIMEOUT_SECONDS" order_status_is "$id" COMPLETED
echo "  ok  PG 조회로 승인 확정 → 주문 …${id: -8} COMPLETED"
expect_eq "결제 상태" APPROVED "$(payment_status "$id")"
expect_eq "PG 원장 (approved,refunded)" "true,false" "$(ledger "$id")"
expect_eq "포인트 잔액" $(( points_before - POINT_AMOUNT )) "$(points "$USER_ID")"
expect_eq "상품 $PRODUCT_ID available" $(( avail_before - 1 )) "$(stock "$PRODUCT_ID" | field available)"
# 판매 확정(OrderCompleted → inventory reserved -1)은 주문 완료 뒤 이벤트로 처리된다. 끝날 때까지 기다려야
# 2b 가 reserved 기준값을 확정 전 값으로 잡지 않는다
wait_until "판매 확정 (reserved 원복)" 30 stock_reserved_is "$reserved_before"
echo "  ok  판매 확정 → 상품 $PRODUCT_ID reserved = $reserved_before"

echo "== 2b. PG 가 Saga 대기 한도보다 늦게 승인 → 결제 취소·보상 → 늦은 승인 자동 환불"
points_before=$(points "$USER_ID")
avail_before=$(stock "$PRODUCT_ID" | field available)
reserved_before=$(stock "$PRODUCT_ID" | field reserved)

fault DELAY "$LONG_DELAY_MILLIS"
id=$(place_order)
wait_until "결제 UNKNOWN" 30 payment_is "$id" UNKNOWN
fault APPROVE 0
echo "  ok  PG 타임아웃 → 결제 UNKNOWN, PG 는 ${LONG_DELAY_MILLIS}ms 뒤 승인 예정"

# Saga 가 Pay 를 다시 묻다가(결제 서비스는 아직 모르므로 응답 없음) 포기하고 결제 취소부터 보상한다
wait_until "주문 CANCELLED" "$TIMEOUT_SECONDS" order_status_is "$id" CANCELLED
echo "  ok  결제 포기 → CancelPayment → 포인트 환불 → 재고 해제 → 주문 …${id: -8} CANCELLED"
order_json "$id" | field cancelReason | grep -q "^timeout: no reply in PAYING" \
  || { echo "FAIL: cancelReason 이 결제 타임아웃이 아님: $(order_json "$id" | field cancelReason)" >&2; exit 1; }
saga_json "$id" | grep -q '"event":"PAYMENT_CANCELLED"' || { echo "FAIL: PAYMENT_CANCELLED 기록 없음" >&2; exit 1; }
expect_eq "포인트 잔액 (환불)" "$points_before" "$(points "$USER_ID")"
expect_eq "상품 $PRODUCT_ID available (해제)" "$avail_before" "$(stock "$PRODUCT_ID" | field available)"
wait_until "재고 reserved 원복" 30 stock_reserved_is "$reserved_before"
expect_eq "취소 직후 결제 상태 (PG 결과 아직 모름)" UNKNOWN "$(payment_status "$id")"

# PG 가 늦게 승인 → 결제 서비스가 조회로 알아채고, 주문은 이미 취소됐으므로 승인 응답 없이 환불한다
wait_until "결제 REFUNDED" "$TIMEOUT_SECONDS" payment_is "$id" REFUNDED
echo "  ok  늦은 승인 확인 → PG 환불 → 결제 REFUNDED"
expect_eq "PG 원장 (approved,refunded)" "true,true" "$(ledger "$id")"
expect_eq "주문 상태 (늦은 승인이 되돌리지 않음)" CANCELLED "$(order_json "$id" | field status)"
echo "  Saga 기록:"
saga_json "$id" | grep -oE '"event":"[A-Z_]+","state":"[A-Z_]+"' | sed -E 's/"event":"([A-Z_]+)","state":"([A-Z_]+)"/    \1 -> \2/'

echo "PASS"
