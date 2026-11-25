#!/usr/bin/env bash
# release 첫 잡: 마지막 배포 이후 main 커밋들의 CI 가 통과했는지 확인한다 (.github/workflows/release.yml)
#
# 입력(env)
#   REPO          owner/name
#   SHA           이번에 배포할 main 커밋
#   PREV_RELEASE  release/eks 의 현재 커밋 (없으면 비움 = 처음 배포)
#   WORKFLOWS     배포 전에 통과해야 하는 워크플로 이름 JSON 배열
#   INTERVAL      대기 간격(초, 기본 30)
#
# 판정: 마지막 배포 커밋 → SHA 사이의 main 커밋(SHA 포함)을 보고, 워크플로마다 **가장 새 커밋의 실행**(그 커밋에서
# 여러 번 돌렸으면 마지막 실행)이 통과해야 한다. CI 는 경로 필터로 돌기 때문에 SHA 하나만 보면 부족하다:
# A 에서 backend 가 깨지고 B 가 frontend 만 바꿨으면 B 에는 backend 실행이 없어 A 의 깨진 코드가 함께 배포된다.
# 반대로 A 다음 커밋에서 backend 가 다시 돌아 통과했다면 그 결과가 A 를 대신한다(그 코드가 A 의 변경을 포함하므로).
# 아직 도는 실행이 있으면 기다리고, 실패·취소가 있으면 exit 1 (배포하지 않음).
set -euo pipefail

: "${REPO:?}" "${SHA:?}" "${WORKFLOWS:?}"
INTERVAL=${INTERVAL:-30}

# 마지막 배포 커밋: release-tags.yaml 의 releasedCommit. 옛 형식(파일 없음)이면 release/eks 커밋 자체가 main 커밋
base=""
if [ -n "${PREV_RELEASE:-}" ]; then
  base=$(git show "$PREV_RELEASE:deploy/argocd/apps/release-tags.yaml" 2>/dev/null \
    | sed -n 's/^releasedCommit: *//p' || true)
  [ -n "$base" ] || base=$(git rev-parse "$PREV_RELEASE^{commit}")
fi

# 보는 커밋들 (새 것부터). 기준이 SHA 의 조상이 아니면(수동 실행 등) SHA 하나만
if [ -n "$base" ] && git merge-base --is-ancestor "$base" "$SHA" 2>/dev/null; then
  mapfile -t commits < <(git rev-list --first-parent "$base..$SHA")
else
  commits=("$SHA")
fi
[ "${#commits[@]}" -gt 0 ] || { echo "새 커밋 없음"; exit 0; }
echo "확인할 main 커밋 ${#commits[@]}개: ${commits[*]:0:10}"

while true; do
  # 커밋 순서(0 = 가장 새 것)를 붙인 실행 목록: 이름, 상태, 결론, 생성 시각, URL, 순서
  runs=""
  for i in "${!commits[@]}"; do
    runs+=$(gh api "repos/$REPO/actions/runs?head_sha=${commits[$i]}&event=push&per_page=100" --paginate \
      --jq ".workflow_runs[] | [.name, .status, (.conclusion // \"\"), .created_at, .html_url, \"$i\"] | @tsv")
    runs+=$'\n'
  done

  # 워크플로마다 가장 새 커밋의 마지막 실행
  picked=$(printf '%s' "$runs" | jq -Rr --argjson w "$WORKFLOWS" '
    [inputs | select(length > 0) | split("\t") | select(.[0] as $n | $w | index($n))]
    | group_by(.[0])
    | map((map(.[5] | tonumber) | min) as $o | map(select((.[5] | tonumber) == $o)) | max_by(.[3]))
    | .[] | @tsv' -n)

  pending=$(printf '%s\n' "$picked" | awk -F'\t' 'NF && $2 != "completed" {printf "%s ", $1}')
  bad=$(printf '%s\n' "$picked" | awk -F'\t' 'NF && $2 == "completed" && $3 !~ /^(success|skipped|neutral)$/ {print $1, $3, $5}')
  total=$(printf '%s\n' "$picked" | awk 'NF' | wc -l)

  if [ -n "$bad" ]; then
    echo "::error::main CI 실패 → 배포하지 않음"
    printf '%s\n' "$bad"
    exit 1
  fi
  if [ -z "$pending" ]; then
    echo "main CI 워크플로 $total 개 통과"
    exit 0
  fi
  echo "기다리는 중: $pending"
  sleep "$INTERVAL"
done
