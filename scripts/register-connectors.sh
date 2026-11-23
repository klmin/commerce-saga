#!/usr/bin/env bash
# Debezium outbox 커넥터 등록 (infra/connect/<서비스>-outbox.json → Kafka Connect)
#
# 준비: 인프라(connect 포함)를 띄우고, 서비스를 한 번 이상 띄워 Flyway 가 outbox 테이블을 만든 뒤 실행한다
#       (publication 을 outbox 테이블 기준으로 만들기 때문). 서비스는 OUTBOX_RELAY_ENABLED=false 로 띄운다
# 실행: scripts/register-connectors.sh            # 네 커넥터 등록(이미 있으면 설정 갱신) 후 RUNNING 대기
#       scripts/register-connectors.sh status     # 커넥터·태스크 상태
#
# PUT /connectors/<이름>/config 라 여러 번 실행해도 된다(멱등)
set -euo pipefail

CONNECT_URL=${CONNECT_URL:-http://localhost:8088}
CONNECTOR_DIR=${CONNECTOR_DIR:-infra/connect}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
CONNECTORS=${CONNECTORS:-"order-outbox inventory-outbox point-outbox payment-outbox"}

state() { # name -> "<커넥터 상태> <태스크 상태들>" (예: RUNNING RUNNING)
  curl -sf "$CONNECT_URL/connectors/$1/status" | grep -oE '"state":"[A-Z]+"' | sed -E 's/"state":"([A-Z]+)"/\1/' | tr '\n' ' '
}

status() {
  for c in $CONNECTORS; do
    echo "$c: $(state "$c" || echo 'NOT FOUND')"
  done
}

if [ "${1:-}" = "status" ]; then
  status
  exit 0
fi

for c in $CONNECTORS; do
  file="$CONNECTOR_DIR/$c.json"
  out=$(mktemp)
  code=$(curl -s -o "$out" -w '%{http_code}' -X PUT -H 'Content-Type: application/json' \
    --data-binary "@$file" "$CONNECT_URL/connectors/$c/config")
  if [ "$code" != "200" ] && [ "$code" != "201" ]; then
    echo "FAIL: $c 등록 실패 (HTTP $code)" >&2
    cat "$out" >&2
    exit 1
  fi
  rm -f "$out"
  echo "registered: $c"
done

# 커넥터와 태스크가 모두 RUNNING 이 될 때까지 기다린다 (slot·publication 생성, 스냅숏 포함)
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
for c in $CONNECTORS; do
  while :; do
    s=$(state "$c" || true)
    if [ "$s" = "RUNNING RUNNING " ]; then
      break
    fi
    if echo "$s" | grep -q FAILED || [ "$(date +%s)" -gt "$deadline" ]; then
      echo "FAIL: $c 상태 '$s'" >&2
      curl -s "$CONNECT_URL/connectors/$c/status" >&2 || true
      exit 1
    fi
    sleep 1
  done
done
status
