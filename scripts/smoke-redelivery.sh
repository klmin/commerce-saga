#!/usr/bin/env bash
# 시나리오 3. 중복 전달 (docs/scenarios/03-duplicate-delivery.md)
#
# 이미 처리한 메시지를 컨슈머 오프셋을 되감아 전부 다시 전달해도 재고·포인트가 다시 바뀌지 않고,
# 응답·이벤트도 새로 나가지 않는지(= 메시지 단위 멱등) 확인한다.
#
# 순서 (CI e2e 와 같다)
#   1. smoke-saga.sh 로 주문을 몇 건 처리해 둔다
#   2. scripts/smoke-redelivery.sh snapshot        # 재고·포인트·토픽 끝 오프셋 기록
#   3. 네 서비스를 내린다
#   4. scripts/rewind-offsets.sh 로 각 서비스 그룹을 처음(--to-earliest)으로 되감는다
#   5. 네 서비스를 다시 띄운다
#   6. LOG_DIR=logs scripts/smoke-redelivery.sh verify
#      다시 받은 메시지를 모두 처리(lag 0)할 때까지 기다린 뒤 2 와 비교한다.
#      LOG_DIR 을 주면 서비스 로그에서 "이미 처리한 ... 건너뜀" 이 찍혔는지도 본다
set -euo pipefail
# access token (ADR 0008). 개발용 시드 사용자 1~4, 관리자 100
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID (product_id)
source "$(dirname "$0")/lib/products.sh"
# Windows Git Bash 가 컨테이너 안 경로(/opt/kafka/...)를 윈도 경로로 바꾸지 않게 한다 (리눅스에서는 영향 없음)
export MSYS_NO_PATHCONV=1

INVENTORY_URL=${INVENTORY_URL:-http://localhost:8082}
POINT_URL=${POINT_URL:-http://localhost:8083}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
SNAPSHOT=${SNAPSHOT:-${TMPDIR:-/tmp}/commerce-redelivery.snapshot}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-90}

GROUPS_TO_CHECK=(inventory-service point-service payment-service order-service)
TOPICS=(inventory.command point.command payment.command inventory.reply point.reply payment.reply order.events)

kafka() {
  docker compose -f "$COMPOSE_FILE" exec -T kafka "$@"
}

# 토픽의 모든 파티션 끝 오프셋 합 = 지금까지 쌓인 메시지 수
end_offset() {
  local out
  out=$(kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic "$1") || {
    echo "FAIL: $1 오프셋 조회 실패" >&2
    exit 1
  }
  echo "$out" | awk -F: '{ sum += $3 } END { print sum + 0 }'
}

field() {
  sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1
}

state() {
  for p in 1 2 3 4 5; do   # 시드 상품 1~5 (스냅숏 키는 번호, 조회는 UUID)
    s=$(curl -sf "$INVENTORY_URL/stocks/$(product_id "$p")")
    echo "stock.$p.available=$(echo "$s" | field available)"
    echo "stock.$p.reserved=$(echo "$s" | field reserved)"
  done
  for u in 1 2 3 4; do
    echo "points.$u=$(curl -sf "$POINT_URL/points/me" -H "$(auth $u)" | field balance)"
  done
  local offset
  for t in "${TOPICS[@]}"; do
    offset=$(end_offset "$t")   # 대입문이라 조회 실패 시 set -e 로 멈춘다
    echo "topic.$t=$offset"
  done
}

# 그룹의 모든 파티션에 컨슈머가 붙어 있고 lag 가 0 이면 성공.
# 메시지가 한 번도 없던 파티션(LOG-END-OFFSET 0)은 커밋한 오프셋이 없어 LAG 가 "-" 로 나오므로 따라잡은 것으로 본다
caught_up() {
  kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group "$1" 2>/dev/null \
    | awk -v g="$1" '$1 == g { rows++; if (($6 != "0" && $5 != "0") || $7 == "-") bad++ } END { exit (rows > 0 && bad == 0) ? 0 : 1 }'
}

wait_caught_up() {
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  for g in "${GROUPS_TO_CHECK[@]}"; do
    until caught_up "$g"; do
      if [ "$(date +%s)" -gt "$deadline" ]; then
        echo "FAIL: $g 가 다시 받은 메시지를 다 처리하지 못함" >&2
        kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group "$g" >&2 || true
        return 1
      fi
      sleep 1
    done
    echo "  ok  $g lag 0"
  done
}

case "${1:-}" in
  snapshot)
    state > "$SNAPSHOT"
    echo "snapshot -> $SNAPSHOT"
    cat "$SNAPSHOT"
    ;;
  verify)
    echo "== 3. 오프셋 되감기로 전부 다시 전달 -> 재고·포인트·응답 변화 없음"
    wait_caught_up
    # lag 0 은 오프셋 커밋 기준이라, 마지막 처리의 outbox relay 가 끝날 시간을 조금 준다
    sleep 2
    state > "$SNAPSHOT.after"
    if ! diff -u "$SNAPSHOT" "$SNAPSHOT.after"; then
      echo "FAIL: 다시 전달된 메시지가 상태를 바꾸거나 메시지를 새로 냈다" >&2
      exit 1
    fi
    echo "  ok  재고·포인트·토픽 끝 오프셋 모두 그대로 ($(wc -l < "$SNAPSHOT") 항목)"
    if [ -n "${LOG_DIR:-}" ]; then
      for svc in "${GROUPS_TO_CHECK[@]}"; do
        n=$(grep -c "이미 처리한" "$LOG_DIR/$svc.log" || true)
        if [ "$n" -lt 1 ]; then
          echo "FAIL: $svc 로그에 건너뜀 기록 없음 (되감기가 안 됐을 수 있음)" >&2
          exit 1
        fi
        echo "  ok  $svc 재전달 ${n}건 건너뜀"
      done
    fi
    echo "PASS"
    ;;
  *)
    echo "usage: $0 snapshot|verify" >&2
    exit 1
    ;;
esac
