#!/usr/bin/env bash
# 시나리오 15. PG 장애가 이어지면 서킷 브레이커가 열려 결제를 바로 실패시키고(즉시 보상), 복구되면 다시 닫힌다
# (docs/scenarios/15-pg-circuit-breaker.md, PROGRESS 4-4절 ③)
#
# 준비: 인프라, 서비스 전부. payment-service 는 열린 뒤 기다림을 짧게 띄운다: PAYMENT_CB_OPEN_WAIT=5s
# 실행: scripts/smoke-pg-circuit.sh
#
# 확인하는 것
#   15a. PG 가 클라이언트 타임아웃(3초)보다 늦게 응답하면 결제마다 UNKNOWN. 최소 호출 수(10)만큼 쌓이면 회로가 열린다
#   15b. 열린 동안의 주문은 PG 를 부르지 않고 몇 초 안에 결제 실패 → 보상 → CANCELLED ("circuit open")
#   15c. PG 를 복구하고 기다림(open-wait)이 지나면 시험 호출이 성공해 회로가 닫히고 주문이 다시 COMPLETED
# 회로를 여는 동안 만든 UNKNOWN 결제는 보정(ADR 0007)이 PG 조회로 확정한다(이 스크립트는 기다리지 않음)
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
USER_ID=${USER_ID:-4}
PRODUCT_ID=${PRODUCT_ID:-2}
UNIT_PRICE=${UNIT_PRICE:-25000}
MIN_CALLS=${MIN_CALLS:-10}
OPEN_WAIT_SECONDS=${OPEN_WAIT_SECONDS:-5}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-120}

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
fault() { # mode delayMillis
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":$2}" > /dev/null
}
circuit() { # 0 닫힘, 1 열림, 2 반열림
  curl -sf "$PAYMENT_URL/actuator/prometheus" | sed -nE 's/^payment_pg_circuit_state(\{[^}]*\})? ([0-9.]+).*/\2/p' | head -1 | cut -d. -f1
}
place_order() {
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" \
    -d "{\"lines\":[{\"productId\":$PRODUCT_ID,\"quantity\":1,\"unitPrice\":$UNIT_PRICE}],\"pointAmount\":0}" | field orderId
}
order_status() { curl -sf "$ORDER_URL/orders/$1" -H "$(auth $USER_ID)" | field status; }
wait_until() { # description seconds command...
  local what=$1 deadline=$(( $(date +%s) + $2 ))
  shift 2
  until "$@"; do
    [ "$(date +%s)" -le "$deadline" ] || fail "timeout waiting for $what"
    sleep 0.5
  done
}
circuit_is() { [ "$(circuit)" = "$1" ]; }
status_is() { [ "$(order_status "$1")" = "$2" ]; }

trap 'fault APPROVE 0 || true' EXIT
[ "$(circuit)" = 0 ] || fail "시작할 때 회로가 닫혀 있지 않음 ($(circuit))"

echo "== 15a. PG 지연(5초 > 타임아웃 3초) 동안 주문 $MIN_CALLS 건 → 회로 열림"
fault DELAY 5000
for _ in $(seq 1 "$MIN_CALLS"); do place_order > /dev/null; done
wait_until "circuit OPEN" "$TIMEOUT_SECONDS" circuit_is 1
ok "payment_pg_circuit_state = 1 (열림)"

echo "== 15b. 열린 동안의 주문은 PG 를 부르지 않고 바로 실패 → 보상 → CANCELLED"
start=$(date +%s)
order=$(place_order)
wait_until "order CANCELLED" 30 status_is "$order" CANCELLED
reason=$(curl -sf "$ORDER_URL/orders/$order" -H "$(auth $USER_ID)" | field cancelReason)
case $reason in *"circuit open"*) ;; *) fail "취소 사유가 회로 열림이 아님: $reason" ;; esac
ok "…${order: -8} CANCELLED $(( $(date +%s) - start ))초, 사유: $reason"

echo "== 15c. PG 복구 → ${OPEN_WAIT_SECONDS}초 뒤 시험 호출 성공 → 닫힘, 주문 COMPLETED"
fault APPROVE 0
sleep $(( OPEN_WAIT_SECONDS + 1 ))
for _ in 1 2 3; do
  order=$(place_order)
  wait_until "order COMPLETED" 60 status_is "$order" COMPLETED
done
wait_until "circuit CLOSED" 30 circuit_is 0
ok "시험 호출 3건 COMPLETED, payment_pg_circuit_state = 0 (닫힘)"

echo "PASS"
