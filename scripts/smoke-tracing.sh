#!/usr/bin/env bash
# 시나리오 11. 주문 한 건이 서비스 다섯 개를 거치는 동안 trace 하나로 이어지는지 (docs/scenarios/11-distributed-tracing.md)
#
# 준비: 인프라(tempo 포함)와 다섯 서비스를 띄운다. relay 모드는 상관없다 (스케줄러 / Debezium)
# 실행: scripts/smoke-tracing.sh
#
# 확인하는 것
#   11a. 정상 주문: 요청에 traceparent 를 직접 넣고, 그 trace id 하나에 다섯 서비스의 span 이 모두 달리는지
#        (HTTP → inventory.command → inventory.reply → point.command → … → order.events 를 받는 inventory·notification)
#   11b. 결제 실패 주문: 보상(RefundPoint, ReleaseStock)까지 같은 trace 에 이어지는지
#   11c. 그 trace 안에서 찍은 로그가 trace_id 와 함께 Loki 에 들어와, trace id 로 여러 서비스의 로그가 찾히는지
#        (서비스 → OTLP → Alloy → Loki. Grafana 의 span → 로그, 로그 → trace 링크가 이것을 쓴다)
# 메시지는 outbox 를 거치므로 trace 문맥은 outbox.traceparent → Kafka 헤더 traceparent → 리스너 observation 으로 넘어간다 (ADR 0013)
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID·가격 (product_id, seed_price)
source "$(dirname "$0")/lib/products.sh"

ORDER_URL=${ORDER_URL:-http://localhost:8081}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
TEMPO_URL=${TEMPO_URL:-http://localhost:3210}
LOKI_URL=${LOKI_URL:-http://localhost:3110}
USER_ID=${USER_ID:-1}
# 상품 id (UUID). 시드 번호 1~5 로 줘도 된다
PRODUCT_ID=$(product_id "${PRODUCT_ID:-1}")
# 상품 시드 가격 (product-service V2). 화면 가격(unitPrice)이 서버 가격과 다르면 주문이 409 (ADR 0016)
UNIT_PRICE=${UNIT_PRICE:-$(seed_price "$PRODUCT_ID")}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}

field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
hex() { od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'; }

wait_until() { # description timeout-seconds command...
  local what=$1 deadline=$(( $(date +%s) + $2 ))
  shift 2
  while [ "$(date +%s)" -le "$deadline" ]; do
    if "$@"; then return 0; fi
    sleep 1
  done
  echo "FAIL: timeout waiting for $what" >&2
  return 1
}

fault() { # mode
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":0}" > /dev/null
}
trap 'fault APPROVE || true' EXIT

# 요청에 traceparent 를 직접 넣어 trace id 를 알고 시작한다 (W3C: 00-<trace 32hex>-<span 16hex>-01)
place_order() { # trace-id
  curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" \
    -H "traceparent: 00-$1-$(hex 8)-01" \
    -d "{\"lines\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1,\"unitPrice\":$UNIT_PRICE}],\"pointAmount\":100}" \
    | field orderId
}

order_status_is() { [ "$(curl -sf "$ORDER_URL/orders/$1" -H "$(auth $USER_ID)" | field status)" = "$2" ]; }

trace_json() { curl -sf "$TEMPO_URL/api/traces/$1" || true; }
services_in() { grep -oE '"service.name","value":\{"stringValue":"[^"]+"' | sed -E 's/.*"stringValue":"//; s/"$//' | sort -u | tr '\n' ' '; }
spans_in() { grep -oE '"name":"[a-z.]+ process"|"name":"http post /orders"' | sed -E 's/"name":"//; s/"$//' | sort | uniq -c; }

# span 은 각 서비스가 묶어서 내보내므로(기본 몇 초) 다 모일 때까지 기다린다
trace_has() { # trace-id expected-span-count
  local n
  n=$(trace_json "$1" | grep -oE '"name":"[a-z.]+ process"|"name":"http post /orders"' | wc -l)
  [ "$n" -ge "$2" ]
}

has_services() { # trace-id
  local got s
  got=$(trace_json "$1" | services_in)
  for s in order-service inventory-service point-service payment-service notification-service; do
    case " $got" in
      *" $s "*) ;;
      *) return 1 ;;
    esac
  done
}

# span 수가 먼저 차도(재시도 등으로 span 이 하나 더 생기면) 늦게 내보낸 서비스의 span 은 아직 없을 수 있어 기다린다
expect_services() { # trace-id
  wait_until "trace $1 에 다섯 서비스" "$TIMEOUT_SECONDS" has_services "$1" \
    || { echo "FAIL: trace $1 에 빠진 서비스 (있는 서비스: $(trace_json "$1" | services_in))" >&2; exit 1; }
  echo "  ok  trace 하나에 다섯 서비스: $(trace_json "$1" | services_in)"
}

echo "== 11a. 정상 주문 → trace 하나에 다섯 서비스"
trace=$(hex 16)
id=$(place_order "$trace")
wait_until "주문 COMPLETED" "$TIMEOUT_SECONDS" order_status_is "$id" COMPLETED
# http 1 + 커맨드 3 + 응답 3 + order.events 2(inventory 판매 확정, notification) = 9
wait_until "trace 에 span 9개" "$TIMEOUT_SECONDS" trace_has "$trace" 9
echo "  ok  주문 …${id: -8} COMPLETED, trace $trace"
expect_services "$trace"
trace_json "$trace" | spans_in | sed 's/^/      /'
# 리스너 span 에 메시지 키(orderId)가 붙어 있어 trace id 를 몰라도 주문 id 로 찾을 수 있다 (MessageTracingConvention)
search_by_order() { # order-id → 찾은 trace id 들 (Tempo 는 앞의 0 을 뗀다)
  curl -sf -G "$TEMPO_URL/api/search" \
    --data-urlencode "q={ span.messaging.kafka.message.key = \"$1\" }" \
    --data-urlencode "start=$(( $(date +%s) - 600 ))" --data-urlencode "end=$(( $(date +%s) + 5 ))" \
    | { grep -oE '"traceID":"[0-9a-f]+"' || true; } | sed -E 's/.*:"0*//; s/"$//' | sort -u | tr '\n' ' '
}
found_only() { [ "$(search_by_order "$1")" = "$(echo "$2" | sed -E 's/^0*//') " ]; }
# 검색 색인은 trace 조회보다 조금 늦게 반영된다
wait_until "주문 id 로 trace 검색" "$TIMEOUT_SECONDS" found_only "$id" "$trace" \
  || { echo "FAIL: 주문 id 로 찾은 trace ($(search_by_order "$id"))가 $trace 하나가 아님" >&2; exit 1; }
echo "  ok  주문 id 로 검색 → 같은 trace 하나"

echo "== 11b. 결제 실패 → 보상까지 같은 trace"
fault DECLINE
trace=$(hex 16)
id=$(place_order "$trace")
wait_until "주문 CANCELLED" "$TIMEOUT_SECONDS" order_status_is "$id" CANCELLED
fault APPROVE
# http 1 + 정방향 커맨드·응답 6 + 보상 커맨드·응답 4(RefundPoint, ReleaseStock) + order.events 1(notification) = 12
wait_until "trace 에 span 12개" "$TIMEOUT_SECONDS" trace_has "$trace" 12
echo "  ok  주문 …${id: -8} CANCELLED, trace $trace"
expect_services "$trace"
trace_json "$trace" | spans_in | sed 's/^/      /'

echo "== 11c. 같은 trace 의 로그가 Loki 에 (trace_id 로 검색)"
logs_of() { # trace-id → Loki 응답
  curl -sf -G "$LOKI_URL/loki/api/v1/query_range" \
    --data-urlencode "query={service_name=~\".+\"} | trace_id=\"$1\"" \
    --data-urlencode "start=$(( $(date +%s) - 600 ))000000000" --data-urlencode "limit=200" || true
}
log_services() { logs_of "$1" | { grep -oE '"service_name":"[^"]+"' || true; } | sed -E 's/.*:"//; s/"$//' | sort -u | tr '\n' ' '; }
# Saga 진행(order-service INFO)과 취소 알림(notification-service INFO)은 이 trace 의 리스너 span 안에서 찍힌다
has_logs() { # trace-id
  local got
  got=" $(log_services "$1")"
  [[ "$got" == *" order-service "* && "$got" == *" notification-service "* ]]
}
# 로그도 서비스가 묶어서 내보내고(기본 1초) Alloy 가 다시 묶어 보낸다
wait_until "trace $trace 의 로그" "$TIMEOUT_SECONDS" has_logs "$trace" \
  || { echo "FAIL: trace $trace 로 찾은 로그의 서비스: $(log_services "$trace")" >&2; exit 1; }
echo "  ok  trace_id 로 찾은 로그의 서비스: $(log_services "$trace")"

echo "PASS"
