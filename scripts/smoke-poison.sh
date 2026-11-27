#!/usr/bin/env bash
# 시나리오 6. poison message → DLT 격리 → 재처리 (docs/scenarios/06-poison-message-dlt.md)
#
# 준비: 인프라와 inventory-service(8082)를 띄운다. 나머지 서비스는 없어도 된다
# 실행: scripts/smoke-poison.sh
#
# 확인하는 것
#   a. 깨진 메시지(JSON 아님)는 재시도 없이 inventory.command.DLT 로 옮겨지고, 같은 파티션의 다음 메시지는 막히지 않는다
#   b. scripts/dlt.sh list 로 실패 원인과 원래 위치를 볼 수 있다
#   c. 버그로 DLT 에 갔던 정상 메시지를 (버그를 고친 뒤) 재처리하면 처리되고 응답이 나간다
#   d. 같은 메시지를 또 재처리해도 한 번만 반영된다 (메시지 id 그대로 → processed_message)
#
# c 의 "버그로 DLT 에 갔던 정상 메시지"는 버그 있는 버전을 따로 띄울 수 없어, Spring 이 DLT 에 남기는 모양
# (원본 헤더 + kafka_dlt-original-topic) 그대로 DLT 에 직접 넣어 흉내 낸다.
set -euo pipefail
# Windows Git Bash 가 컨테이너 안 경로(/opt/kafka/...)를 윈도 경로로 바꾸지 않게 한다 (리눅스에서는 영향 없음)
export MSYS_NO_PATHCONV=1
# 시드 상품 고정 UUID (PRODUCT_KEYBOARD 등)
source "$(dirname "$0")/lib/products.sh"

INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-30}
DLT=inventory.command.DLT

kafka() {
  docker compose -f "$COMPOSE_FILE" exec -T kafka "$@"
}

uuid() {
  cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'
}

produce() { # topic headers(k:v,k:v) key value
  printf '%s\t%s\t%s\n' "$2" "$3" "$4" | kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:29092 --topic "$1" \
    --reader-property parse.key=true --reader-property parse.headers=true > /dev/null
}

# inventory.reply 에서 key 의 응답 타입들 (한 줄에 하나)
reply_types() { # key
  kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic inventory.reply \
    --from-beginning --timeout-ms 3000 --formatter-property print.key=true --formatter-property print.headers=true \
    2>/dev/null | awk -F'\t' -v k="$1" '$2 == k { print $1 }' | grep -oE 'type:[A-Za-z]+' | sed 's/type://' || true
}

wait_reply() { # key type
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    if reply_types "$1" | grep -qx "$2"; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL: inventory.reply 에 $2 (key=$1) 없음" >&2
  return 1
}

dlt_position() { # key -> P:O
  scripts/dlt.sh list "$DLT" | sed -nE "s/^\[([0-9]+:[0-9]+)\] key=$1 .*/\1/p" | tail -1
}

reserved() {
  curl -sf "$INVENTORY_URL/stocks/$PRODUCT_KEYBOARD" | sed -nE 's/.*"reserved":([0-9]+).*/\1/p'
}

expect_eq() { # name expected actual
  if [ "$2" != "$3" ]; then
    echo "FAIL: $1 expected '$2' but was '$3'" >&2
    exit 1
  fi
  echo "  ok  $1 = $3"
}

echo "== 6a. 깨진 메시지는 DLT 로 격리, 같은 주문(파티션)의 다음 메시지는 정상 처리"
poison_order=$(uuid)
produce inventory.command "id:$(uuid),type:ReserveStock" "$poison_order" '{not json'
# 같은 key = 같은 파티션. 앞의 깨진 메시지가 파티션을 막고 있으면 이 메시지는 처리되지 않는다
produce inventory.command "id:$(uuid),type:ReleaseStock" "$poison_order" "{\"orderId\":\"$poison_order\"}"
wait_reply "$poison_order" StockReleased
echo "  ok  다음 메시지(ReleaseStock) 처리됨 -> 파티션 막힘 없음"

echo "== 6b. DLT 조회: 실패 원인과 원래 위치"
listing=$(scripts/dlt.sh list "$DLT")
line=$(echo "$listing" | grep -A2 "key=$poison_order " || true)
if [ -z "$line" ]; then
  echo "FAIL: $DLT 에 깨진 메시지(key=$poison_order) 없음" >&2
  exit 1
fi
echo "$line" | sed 's/^/      /'
echo "$line" | grep -q "from=inventory.command/" || { echo "FAIL: 원래 위치 없음" >&2; exit 1; }
echo "$line" | grep -qi "jackson" || { echo "FAIL: 실패 원인(JSON 파싱)이 안 보임" >&2; exit 1; }
echo "  ok  깨진 메시지가 원인(JSON 파싱 실패)과 함께 보존됨"

echo "== 6c. 고친 뒤 재처리: DLT 의 정상 메시지를 원래 토픽으로 다시 보냄"
order=$(uuid)
message_id=$(uuid)
before=$(reserved)
produce "$DLT" "id:$message_id,type:ReserveStock,kafka_dlt-original-topic:inventory.command" "$order" \
  "{\"orderId\":\"$order\",\"lines\":[{\"productId\":\"$PRODUCT_KEYBOARD\",\"quantity\":1}]}"
position=$(dlt_position "$order")
[ -n "$position" ] || { echo "FAIL: DLT 에 넣은 메시지를 찾지 못함" >&2; exit 1; }
scripts/dlt.sh replay "$DLT" --offset "$position" | sed 's/^/      /'
wait_reply "$order" StockReserved
expect_eq "상품 1 reserved(재처리 후)" "$((before + 1))" "$(reserved)"

echo "== 6d. 같은 메시지를 한 번 더 재처리 -> 반영·응답 없음 (id 그대로)"
scripts/dlt.sh replay "$DLT" --offset "$position" | sed 's/^/      /'
sleep 5
expect_eq "StockReserved 응답 수" 1 "$(reply_types "$order" | grep -cx StockReserved || true)"
expect_eq "상품 1 reserved(두 번째 재처리 후)" "$((before + 1))" "$(reserved)"

# 정리: 이 주문은 Saga 없이 만든 예약이라 풀어 둔다
produce inventory.command "id:$(uuid),type:ReleaseStock" "$order" "{\"orderId\":\"$order\"}"
wait_reply "$order" StockReleased
echo "PASS"
