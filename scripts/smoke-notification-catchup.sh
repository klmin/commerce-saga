#!/usr/bin/env bash
# 시나리오 8. 알림 서비스 장애 격리 (docs/scenarios/08-notification-down.md)
#
# smoke-saga.sh 를 notification-service 없이 먼저 돌린 뒤(주문은 정상 완료·취소),
# notification-service 를 띄우고 이 스크립트를 실행한다.
# 내려가 있던 동안 쌓인 order.events 를 이어서 처리해 알림이 만들어지는지 확인한다.
set -euo pipefail

NOTIFICATION_URL=${NOTIFICATION_URL:-http://localhost:8085}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-30}

count_type() { # userId type
  curl -sf "$NOTIFICATION_URL/notifications/me?size=50" -H "X-User-Id: $1" | grep -o "\"type\":\"$2\"" | wc -l | tr -d ' '
}

wait_for() { # userId type
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while [ "$(date +%s)" -le "$deadline" ]; do
    if [ "$(count_type "$1" "$2")" -ge 1 ]; then
      echo "  ok  user $1 알림 $2 $(count_type "$1" "$2")건"
      return 0
    fi
    sleep 0.5
  done
  echo "FAIL: user $1 알림 $2 없음" >&2
  return 1
}

echo "== 8. 알림 서비스가 내려가 있던 동안의 주문 결과를 다시 뜬 뒤 알림으로 남김"
wait_for 1 OrderCompleted   # smoke-saga 1번 (정상 주문)
wait_for 1 OrderCancelled   # smoke-saga 2번 (품절)
wait_for 2 OrderCancelled   # smoke-saga 3번 (결제 실패 보상)
echo "PASS"
