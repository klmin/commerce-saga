#!/usr/bin/env bash
# 시나리오 12. 장애를 넣으면 경보가 실제로 firing 되는지 (docs/scenarios/12-metrics-alerts.md)
#
# 준비: 인프라(prometheus, alertmanager, kafka-exporter 포함)와 다섯 서비스를 띄운다. 타임아웃은 CI e2e 와 같은 값
#   order-service:   SAGA_STEP_TIMEOUT=10s SAGA_PAYMENT_MAX_RESENDS=2 SAGA_TIMEOUT_SCAN_INTERVAL=1s
#   payment-service: PG_CLIENT_TIMEOUT=3s(기본) PAYMENT_RECONCILE_INTERVAL=1s
# 실행: scripts/smoke-alerts.sh
#
# 확인하는 것 (경보 규칙: infra/prometheus/rules/commerce.yml)
#   12a. 깨진 메시지가 payment.command.DLT 로 가면 DeadLetterMessage{topic="payment.command.DLT"} 가 firing
#        (메트릭 출처: kafka-exporter 의 토픽 끝 오프셋)
#   12b. PG 를 45초 늦추면 결제가 UNKNOWN 으로 30초 넘게 남아 PaymentResultUnknown 이 firing,
#        Saga 가 결제를 포기하면 SagaTimeoutGiveUp 이 firing (메트릭 출처: payment·order-service /actuator/prometheus).
#        PG 가 늦게 승인해 환불까지 끝나면 PaymentResultUnknown 이 풀린다
#   12c. Prometheus 가 firing 경보를 Alertmanager 로 보내 DeadLetterMessage 가 Alertmanager 에 도착하고 receiver 가 붙는다
#        (DISCORD_WEBHOOK_URL 이 있으면 discord, 없으면 null. Discord 로 실제 나갔는지는 채널에서 본다)
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"
export MSYS_NO_PATHCONV=1

ORDER_URL=${ORDER_URL:-http://localhost:8081}
PAYMENT_URL=${PAYMENT_URL:-http://localhost:8084}
PROMETHEUS_URL=${PROMETHEUS_URL:-http://localhost:9091}
ALERTMANAGER_URL=${ALERTMANAGER_URL:-http://localhost:9095}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
USER_ID=${USER_ID:-1}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-120}

uuid() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'
}

wait_until() { # description timeout-seconds command...
  local what=$1 deadline=$(( $(date +%s) + $2 ))
  shift 2
  while [ "$(date +%s)" -le "$deadline" ]; do
    if "$@"; then return 0; fi
    sleep 2
  done
  echo "FAIL: timeout waiting for $what" >&2
  echo "  현재 경보: $(alerts_summary)" >&2
  return 1
}

fault() { # mode delayMillis
  curl -sf -X PUT "$PAYMENT_URL/admin/payment-faults" -H "$(auth 100)" -H 'Content-Type: application/json' \
    -d "{\"mode\":\"$1\",\"delayMillis\":$2}" > /dev/null
}
trap 'fault APPROVE 0 || true' EXIT

# Prometheus 경보 목록을 경보 하나에 한 줄로 (jq 없이). 각 줄에 labels·annotations·state 가 들어 있다
alerts() { curl -sf "$PROMETHEUS_URL/api/v1/alerts" | sed 's/},{"labels"/}\n{"labels"/g' || true; }
alerts_summary() {
  alerts | sed -nE 's/.*"alertname":"([A-Za-z]+)".*"state":"([a-z]+)".*/\1(\2)/p' | sort -u | tr '\n' ' '
}
# 경보 하나가 firing 인가. 두 번째 인자를 주면 그 문자열(라벨)이 같은 경보 줄에 있어야 한다
firing() { # alertname [label-fragment]
  alerts | grep "\"alertname\":\"$1\"" | grep '"state":"firing"' | grep -q -- "${2:-}"
}
not_firing() { ! firing "$@"; }
# Prometheus 즉시 조회 결과 숫자 하나 (없으면 0). 경보가 이미 firing 중이어도 이번 장애로 값이 늘었는지 따로 본다
metric() {
  curl -sf -G "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$1" \
    | sed -nE 's/.*"value":\[[0-9.]+,"([0-9.e+-]+)"\].*/\1/p' | head -1 | awk '{ print $1 + 0 } END { if (NR == 0) print 0 }'
}
greater() { awk -v a="$(metric "$1")" -v b="$2" 'BEGIN { exit !(a > b) }'; }

echo "== 12a. 깨진 메시지 → payment.command.DLT → DeadLetterMessage firing"
dlt_query='sum(kafka_topic_partition_current_offset{topic="payment.command.DLT"})'
dlt_before=$(metric "$dlt_query")
order=$(uuid)
printf '%s\t%s\t%s\n' "id:$(uuid),type:Pay" "$order" '{not json' \
  | docker compose -f "$COMPOSE_FILE" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:29092 --topic payment.command \
      --reader-property parse.key=true --reader-property parse.headers=true > /dev/null
wait_until "payment.command.DLT 오프셋 증가" "$TIMEOUT_SECONDS" greater "$dlt_query" "$dlt_before"
echo "  ok  payment.command.DLT 메시지 $dlt_before → $(metric "$dlt_query")"
wait_until "DeadLetterMessage firing" "$TIMEOUT_SECONDS" firing DeadLetterMessage '"topic":"payment.command.DLT"'
echo "  ok  DeadLetterMessage{topic=payment.command.DLT} firing"

# 12b 보다 먼저 본다: 12a 의 DeadLetterMessage 는 DLT 증가 후 5분이 지나면 풀린다
echo "== 12c. Prometheus → Alertmanager: firing 경보가 전달 경로에 도착"
# Prometheus 가 Alertmanager 를 찾았는지 (prometheus.yml 의 alerting.alertmanagers)
am_connected() { curl -sf "$PROMETHEUS_URL/api/v1/alertmanagers" | grep -q '"activeAlertmanagers":\[{'; }
wait_until "Prometheus 의 Alertmanager 연결" 30 am_connected
# Alertmanager 가 받은 경보 하나 (v2 API, 경보 이름으로 거른다). receivers 는 라우팅 결과
am_alert() { curl -sf -G "$ALERTMANAGER_URL/api/v2/alerts" --data-urlencode "filter=alertname=\"$1\"" --data-urlencode 'active=true' || true; }
am_received() { am_alert "$1" | grep -q -- "$2"; }
wait_until "Alertmanager 에 DeadLetterMessage 도착" "$TIMEOUT_SECONDS" am_received DeadLetterMessage '"topic":"payment.command.DLT"'
receiver=$(am_alert DeadLetterMessage | sed -nE 's/.*"receivers":\[\{"name":"([^"]+)".*/\1/p' | head -1)
[ -n "$receiver" ] || { echo "FAIL: DeadLetterMessage 에 receiver 가 없음" >&2; exit 1; }
echo "  ok  Alertmanager 가 DeadLetterMessage{topic=payment.command.DLT} 를 받음 → receiver $receiver (warning: 2분 모았다가 보냄)"

echo "== 12b. PG 지연 45초 → PaymentResultUnknown, SagaTimeoutGiveUp firing → 환불 후 UNKNOWN 경보 해제"
give_up_query='sum(commerce_saga_timeout_give_ups_total)'
give_ups_before=$(metric "$give_up_query")
fault DELAY 45000
id=$(curl -sf -X POST "$ORDER_URL/orders" -H 'Content-Type: application/json' -H "$(auth $USER_ID)" \
  -d '{"lines":[{"productId":1,"quantity":1,"unitPrice":1000}],"pointAmount":100}' \
  | sed -nE 's/.*"orderId":"([^"]+)".*/\1/p')
echo "  주문 …${id: -8}"
payment_status() { curl -s "$PAYMENT_URL/payments/orders/$id" -H "$(auth $USER_ID)" | sed -nE 's/.*"status":"([A-Z]+)".*/\1/p'; }
payment_is() { [ "$(payment_status)" = "$1" ]; }
wait_until "결제 UNKNOWN" 30 payment_is UNKNOWN
fault APPROVE 0   # 이미 PG 에서 처리 중인 요청은 지연 그대로 진행된다
wait_until "포기 횟수 증가" "$TIMEOUT_SECONDS" greater "$give_up_query" "$give_ups_before"
wait_until "SagaTimeoutGiveUp firing" "$TIMEOUT_SECONDS" firing SagaTimeoutGiveUp
echo "  ok  commerce_saga_timeout_give_ups_total $give_ups_before → $(metric "$give_up_query"), SagaTimeoutGiveUp firing (결제 응답을 기다리다 포기 → 결제 취소·보상)"
wait_until "PaymentResultUnknown firing" "$TIMEOUT_SECONDS" firing PaymentResultUnknown
echo "  ok  PaymentResultUnknown firing (UNKNOWN 30초 넘음)"
wait_until "결제 REFUNDED" "$TIMEOUT_SECONDS" payment_is REFUNDED
wait_until "PaymentResultUnknown 해제" "$TIMEOUT_SECONDS" not_firing PaymentResultUnknown
echo "  ok  늦은 승인 → 환불(REFUNDED) → PaymentResultUnknown 해제"
echo "  경보: $(alerts_summary)"

echo "PASS"
