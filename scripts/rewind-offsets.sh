#!/usr/bin/env bash
# 컨슈머 그룹 오프셋 되감기 (시나리오 3. 중복 전달, docs/scenarios/03-duplicate-delivery.md)
#
# 사용: scripts/rewind-offsets.sh <그룹> <토픽>[,<토픽>...] [reset 옵션 (기본 --to-earliest)]
#   scripts/rewind-offsets.sh inventory-service inventory.command,order.events
#   scripts/rewind-offsets.sh point-service point.command --shift-by -10
#
# 그룹 이름은 서비스 이름(spring.application.name)과 같다.
# 그룹의 컨슈머(서비스)가 모두 내려가 있어야 한다. 떠 있으면 Kafka 가 되감기를 거부한다.
set -euo pipefail
# Windows Git Bash 가 컨테이너 안 경로(/opt/kafka/...)를 윈도 경로로 바꾸지 않게 한다 (리눅스에서는 영향 없음)
export MSYS_NO_PATHCONV=1

if [ $# -lt 2 ]; then
  sed -n '4,6p' "$0" >&2
  exit 1
fi

group=$1
topics=$2
shift 2
reset=("$@")
if [ ${#reset[@]} -eq 0 ]; then
  reset=(--to-earliest)
fi

topic_args=()
IFS=, read -ra topic_list <<< "$topics"
for t in "${topic_list[@]}"; do
  topic_args+=(--topic "$t")
done

groups_cmd() {
  docker compose -f "${COMPOSE_FILE:-infra/docker-compose.yml}" exec -T kafka \
    /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --group "$group" "$@"
}

# 서비스가 정상 종료되면 그룹을 바로 떠나지만, 강제 종료(kill -9 등)면 세션 만료(기본 45초)까지 멤버로 남는다.
# 그동안 되감기는 거부되는데 도구는 종료 코드 0 을 돌려주므로, 출력으로 판단하고 기다렸다 다시 한다
deadline=$(( $(date +%s) + ${WAIT_INACTIVE_SECONDS:-90} ))
while true; do
  out=$(groups_cmd "${topic_args[@]}" --reset-offsets "${reset[@]}" --execute 2>&1) || true
  if ! grep -q "Error" <<< "$out" && grep -q "NEW-OFFSET" <<< "$out"; then
    echo "$out"
    exit 0
  fi
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "$out" >&2
    echo "FAIL: $group 오프셋 되감기 실패" >&2
    exit 1
  fi
  echo "$group 그룹이 아직 활성 상태라 기다림..." >&2
  sleep 3
done
