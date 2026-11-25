#!/usr/bin/env bash
# release 첫 잡: 마지막 배포 이후 main 커밋들의 CI 가 통과했는지 확인한다 (.github/workflows/release.yml)
#
# 입력(env)
#   REPO          owner/name
#   SHA           이번에 배포할 main 커밋
#   PREV_RELEASE  release/eks 의 현재 커밋 (없으면 비움 = 처음 배포)
#   WORKFLOWS     배포 전에 통과해야 하는 워크플로 이름 JSON 배열
#   CI_FROM       (선택) 이 커밋부터 SHA 까지만 본다. 앞 커밋의 실패를 뒤 커밋이 고쳤을 때 수동 실행으로 넘긴다
#   INTERVAL      대기 간격(초, 기본 30)
#
# 판정: 마지막 배포 커밋 이후의 main 커밋(SHA 포함) **하나하나**의 CI 실행(같은 커밋을 다시 돌렸으면 마지막 실행)이
# 모두 통과해야 한다. 이번 커밋만 보면 부족하다: CI 는 경로 필터로 돌고 backend 는 바뀐 모듈만 빌드하므로,
# 앞 커밋에서 깨진 워크플로·모듈이 이번 커밋에서는 다시 검증되지 않을 수 있다. 같은 이유로 뒤 커밋의 통과가
# 앞 커밋의 실패를 대신하지 않는다. 뒤 커밋이 고쳤다면 사람이 확인하고 CI_FROM(수동 실행 입력 ci_from)으로 넘긴다.
#
# 실행이 아직 API 에 안 보이는 것과 경로 필터로 안 도는 것을 구분하려고, 문서만 바꾸지 않은 커밋은 codeql 실행
# (문서만 바뀐 경우 말고는 항상 돈다)이 보일 때까지 기다린다. 같은 push 의 워크플로 실행은 함께 만들어진다.
# 아직 도는 실행이 있으면 기다리고, 실패·취소가 있으면 exit 1 (배포하지 않음).
set -euo pipefail

: "${REPO:?}" "${SHA:?}" "${WORKFLOWS:?}"
INTERVAL=${INTERVAL:-30}
ALWAYS=codeql

# 마지막 배포 커밋: release-tags.yaml 의 releasedCommit. 옛 형식(파일 없음)이면 release/eks 커밋 자체가 main 커밋
base=""
if [ -n "${PREV_RELEASE:-}" ]; then
  base=$(git show "$PREV_RELEASE:deploy/argocd/apps/release-tags.yaml" 2>/dev/null \
    | sed -n 's/^releasedCommit: *//p' || true)
  [ -n "$base" ] || base=$(git rev-parse "$PREV_RELEASE^{commit}")
fi

# 보는 커밋들 (새 것부터)
if [ -n "${CI_FROM:-}" ]; then
  from=$(git rev-parse "$CI_FROM^{commit}")
  git merge-base --is-ancestor "$from" "$SHA" || { echo "::error::ci_from($CI_FROM)이 $SHA 의 조상이 아님"; exit 1; }
  mapfile -t commits < <(git rev-list --first-parent "$SHA" | sed "/^$from\$/q")
elif [ -n "$base" ] && git merge-base --is-ancestor "$base" "$SHA" 2>/dev/null; then
  mapfile -t commits < <(git rev-list --first-parent "$base..$SHA")
else
  commits=("$SHA")   # 처음 배포, 또는 기준이 조상이 아님
fi
[ "${#commits[@]}" -gt 0 ] || { echo "새 커밋 없음"; exit 0; }
echo "확인할 main 커밋 ${#commits[@]}개: ${commits[*]:0:10}"

# codeql 이 돌아야 하는 커밋 (문서만 바꾼 커밋 제외. codeql 의 paths-ignore 와 같다)
needs_always=()
for c in "${commits[@]}"; do
  changed=$(git diff --name-only "$c^1" "$c" 2>/dev/null | grep -vE '^docs/|\.md$' || true)
  if [ -n "$changed" ]; then
    needs_always+=("$c")
  fi
done

while true; do
  # 커밋별 실행 목록: 커밋, 이름, 상태, 결론, 생성 시각, URL
  runs=""
  for c in "${commits[@]}"; do
    runs+=$(gh api "repos/$REPO/actions/runs?head_sha=$c&event=push&per_page=100" --paginate \
      --jq ".workflow_runs[] | [\"$c\", .name, .status, (.conclusion // \"\"), .created_at, .html_url] | @tsv")
    runs+=$'\n'
  done

  # (커밋, 워크플로)마다 마지막 실행
  picked=$(printf '%s' "$runs" | jq -Rr --argjson w "$WORKFLOWS" '
    [inputs | select(length > 0) | split("\t") | select(.[1] as $n | $w | index($n))]
    | group_by([.[0], .[1]]) | map(max_by(.[4])) | .[] | @tsv' -n)

  pending=$(printf '%s\n' "$picked" | awk -F'\t' 'NF && $3 != "completed" {printf "%s@%s ", $2, substr($1, 1, 7)}')
  for c in "${needs_always[@]}"; do
    printf '%s\n' "$picked" | awk -F'\t' -v c="$c" -v n="$ALWAYS" '$1 == c && $2 == n {f=1} END {exit !f}' \
      || pending+="$ALWAYS@${c:0:7}(아직 안 보임) "
  done
  bad=$(printf '%s\n' "$picked" | awk -F'\t' 'NF && $3 == "completed" && $4 !~ /^(success|skipped|neutral)$/ {print $2 "@" substr($1, 1, 7), $4, $6}')
  total=$(printf '%s\n' "$picked" | awk 'NF' | wc -l)

  if [ -n "$bad" ]; then
    echo "::error::main CI 실패 → 배포하지 않음. 뒤 커밋이 고쳤다면 그 커밋을 ci_from 으로 수동 실행"
    printf '%s\n' "$bad"
    exit 1
  fi
  if [ -z "$pending" ]; then
    echo "main CI 실행 $total 개 통과"
    exit 0
  fi
  echo "기다리는 중: $pending"
  sleep "$INTERVAL"
done
