#!/usr/bin/env bash
# 시나리오 4. 참여 서비스 다운 중 주문 → 복구 후 이어서 진행 / 오래 다운되면 보상 (docs/scenarios/04-service-down.md)
#
# 준비: 인프라와 네 서비스를 띄운다. order-service 는 단계 타임아웃을 짧게 둔다 (CI e2e 와 같은 값)
#   SAGA_STEP_TIMEOUT=10s SAGA_MAX_RESENDS=3 SAGA_TIMEOUT_SCAN_INTERVAL=1s
# 실행: scripts/smoke-service-down.sh
#   point-service 를 이 스크립트가 직접 내리고(pkill) 다시 띄운다 (java -jar, 로그는 $LOG_DIR/point-service.log 에 이어 씀)
#
# 확인하는 것
#   4a. 짧은 장애: point-service 가 내려간 동안 주문은 USING_POINT 에서 기다리고, order-service 가 UsePoint 를 다시 보낸다.
#       복구되면 밀린 커맨드를 처리해 주문이 COMPLETED. 포인트는 한 번만 차감된다 (재전송은 주문별 기록으로 걸러짐)
#   4b. 긴 장애: 재전송해도 응답이 없으면 order-service 가 포기하고 역순 보상을 시작한다(REFUNDING_POINT).
#       복구되면 밀린 UsePoint 가 먼저 처리되고 RefundPoint 가 뒤따라 포인트가 되돌아오며, 재고도 풀려 CANCELLED.
#       포기한 뒤 늦게 온 PointUsed 응답은 Saga 를 되돌리지 않는다
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
USER_ID=${USER_ID:-2}
PRODUCT_ID=${PRODUCT_ID:-3}
POINT_AMOUNT=${POINT_AMOUNT:-100}
# order-service 의 saga.timeout 값과 맞춘다
STEP_TIMEOUT_SECONDS=${STEP_TIMEOUT_SECONDS:-10}
MAX_RESENDS=${MAX_RESENDS:-3}
JAVA=${JAVA:-java}
LOG_DIR=${LOG_DIR:-logs}
POINT_JAR=${POINT_JAR:-$(ls services/point-service/build/libs/point-service-*.jar 2>/dev/null | grep -v plain | head -1 || true)}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
stock() { curl -sf "$INVENTORY_URL/stocks/$1"; }
points() { curl -sf "$POINT_URL/points/me" -H "$(auth $1)" | field balance; }
order_json() { curl -sf "$ORDER_URL/orders/$1" -H "$(auth $USER_ID)"; }
saga_json() { curl -sf "$ORDER_URL/orders/$1/saga" -H "$(auth $USER_ID)"; }

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

point_up() { curl -sf "$POINT_URL/actuator/health" 2>/dev/null | grep -q UP; }
point_down() {
  point_up && return 1
  [ -n "${POINT_STOP_CMD:-}" ] || ! pgrep -f "point-service-.*\.jar" > /dev/null
}

# 내리고 띄우는 명령은 바꿀 수 있다 (로컬 Windows Git Bash 는 pkill·pgrep 이 java 프로세스를 못 본다)
stop_point() {
  if [ -n "${POINT_STOP_CMD:-}" ]; then eval "$POINT_STOP_CMD"; else pkill -f "point-service-.*\.jar" || true; fi
  wait_until "point-service stopped" 30 point_down
}

start_point() {
  if [ -n "${POINT_START_CMD:-}" ]; then
    eval "$POINT_START_CMD"
  else
    mkdir -p "$LOG_DIR"
    nohup "$JAVA" -jar "$POINT_JAR" >> "$LOG_DIR/point-service.log" 2>&1 &
  fi
  wait_until "point-service UP" 120 point_up
}

# 실패해도 point-service 는 다시 띄워 둔다 (뒤 단계가 쓴다)
cleanup() { point_up || start_point; }
trap cleanup EXIT

place_order() {
  local body="{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":1000}],\"pointAmount\":$POINT_AMOUNT}"
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" -d "$body" \
    | field orderId
}

saga_has() { saga_json "$1" | grep -q "\"event\":\"$2\",\"state\":\"$3\""; }  # orderId event state
saga_state_is() { [ "$(saga_json "$1" | field state)" = "$2" ]; }
order_status_is() { [ "$(order_json "$1" | field status)" = "$2" ]; }
stock_reserved_is() { [ "$(stock "$PRODUCT_ID" | field reserved)" = "$1" ]; }

curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
  -d '{"mode":"APPROVE","delayMillis":0}' > /dev/null

echo "== 4a. point-service 짧은 장애 → 재전송 → 복구 후 COMPLETED"
points_before=$(points "$USER_ID")
avail_before=$(stock "$PRODUCT_ID" | field available)
reserved_before=$(stock "$PRODUCT_ID" | field reserved)

stop_point
echo "  point-service 중단"
id=$(place_order)
# 단계 타임아웃이 지나면 같은 UsePoint 를 다시 보낸다
wait_until "UsePoint 재전송" $(( STEP_TIMEOUT_SECONDS * 2 + 10 )) saga_has "$id" TIMEOUT_RESEND USING_POINT
expect_eq "장애 중 Saga 단계" USING_POINT "$(saga_json "$id" | field state)"
expect_eq "장애 중 주문 상태" PENDING "$(order_json "$id" | field status)"

start_point
echo "  point-service 복구"
wait_until "주문 COMPLETED" "$TIMEOUT_SECONDS" order_status_is "$id" COMPLETED
echo "  ok  복구 후 주문 …${id: -8} COMPLETED"
expect_eq "포인트 잔액 (재전송해도 한 번만 차감)" $(( points_before - POINT_AMOUNT )) "$(points "$USER_ID")"
expect_eq "상품 $PRODUCT_ID available" $(( avail_before - 1 )) "$(stock "$PRODUCT_ID" | field available)"
wait_until "재고 판매 확정" "$TIMEOUT_SECONDS" stock_reserved_is "$reserved_before"

echo "== 4b. point-service 긴 장애 → 재전송 포기 → 역순 보상 → 복구 후 CANCELLED"
points_before=$(points "$USER_ID")
avail_before=$(stock "$PRODUCT_ID" | field available)
reserved_before=$(stock "$PRODUCT_ID" | field reserved)

stop_point
echo "  point-service 중단"
id=$(place_order)
# 재전송 MAX_RESENDS 번 후에도 응답이 없으면 포인트 차감 여부를 모르는 채로 환불부터 되돌린다
wait_until "보상 시작 (REFUNDING_POINT)" $(( STEP_TIMEOUT_SECONDS * (MAX_RESENDS + 2) + 10 )) \
  saga_state_is "$id" REFUNDING_POINT
saga_has "$id" TIMEOUT_GIVE_UP REFUNDING_POINT || { echo "FAIL: TIMEOUT_GIVE_UP 기록 없음" >&2; exit 1; }
echo "  ok  재전송 ${MAX_RESENDS}번 후 포기, REFUNDING_POINT"
expect_eq "보상 중 주문 상태 (보상 끝날 때까지)" PENDING "$(order_json "$id" | field status)"

start_point
echo "  point-service 복구 (밀린 UsePoint → RefundPoint 순서로 처리)"
wait_until "주문 CANCELLED" "$TIMEOUT_SECONDS" order_status_is "$id" CANCELLED
echo "  ok  복구 후 주문 …${id: -8} CANCELLED"
order_json "$id" | field cancelReason | grep -q "^timeout: no reply in USING_POINT" \
  || { echo "FAIL: cancelReason 이 타임아웃이 아님: $(order_json "$id" | field cancelReason)" >&2; exit 1; }
expect_eq "포인트 잔액 (차감 후 환불)" "$points_before" "$(points "$USER_ID")"
expect_eq "상품 $PRODUCT_ID available (해제)" "$avail_before" "$(stock "$PRODUCT_ID" | field available)"
expect_eq "상품 $PRODUCT_ID reserved (해제)" "$reserved_before" "$(stock "$PRODUCT_ID" | field reserved)"
echo "  Saga 기록:"
saga_json "$id" | grep -oE '"event":"[A-Z_]+","state":"[A-Z_]+"' | sed -E 's/"event":"([A-Z_]+)","state":"([A-Z_]+)"/    \1 -> \2/'

echo "PASS"
