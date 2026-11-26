#!/usr/bin/env bash
# release 워크플로가 쓴다: 마지막 배포 이후 바뀐 이미지를 고르고 release-tags.yaml 을 만든다 (docs/ci-cd.md "배포")
#
# 입력(환경변수)
#   SHA           이번에 배포할 main 커밋 (기본 HEAD)
#   PREV_RELEASE  지금 release/eks 가 가리키는 커밋. 비면 처음 → 전부 빌드
#   FORCE_ALL     true 면 전부 빌드 (Actions 수동 실행의 all)
#   OUT           release-tags.yaml 을 쓸 경로 (기본 release-tags.yaml)
# 출력
#   OUT 파일, GITHUB_OUTPUT 이 있으면 build=<빌드할 이미지 이름들>
#
# 비교 기준은 "직전 커밋" 이 아니라 마지막으로 배포된 main 커밋(release/eks 의 release-tags.yaml 에 적힌 releasedCommit)이다.
# 그래서 머지가 몰려 중간 release 가 건너뛰어지거나, release 가 실패해도 다음 실행이 그 사이 바뀐 것을 모두 잡는다.
# 로컬 확인: PREV_RELEASE=$(git rev-parse origin/release/eks) bash scripts/release-plan.sh
set -euo pipefail

SHA=$(git rev-parse "${SHA:-HEAD}")
PREV_RELEASE=${PREV_RELEASE:-}
[ -z "$PREV_RELEASE" ] || PREV_RELEASE=$(git rev-parse "$PREV_RELEASE^{commit}")
FORCE_ALL=${FORCE_ALL:-false}
OUT=${OUT:-release-tags.yaml}
TAGS_PATH=deploy/argocd/apps/release-tags.yaml

# 이미지 이름 = 차트의 image.tags 키 (release.yml 의 SERVICES 와 같아야 한다)
BACKEND="auth-service product-service search-service order-service inventory-service point-service payment-service notification-service gateway"
IMAGES="$BACKEND web admin connect"

declare -A prev_tags=()
base=""
if [ -n "$PREV_RELEASE" ]; then
  if git cat-file -e "$PREV_RELEASE:$TAGS_PATH" 2> /dev/null; then
    file=$(git show "$PREV_RELEASE:$TAGS_PATH")
    base=$(echo "$file" | sed -n 's/^releasedCommit: *//p')
    while read -r name tag; do
      prev_tags[$name]=$tag
    done < <(echo "$file" | sed -n 's/^      \([a-z0-9-]*\): *\([0-9a-f]*\)$/\1 \2/p')
  else
    # 이 방식 이전: release/eks = main 커밋, 모든 이미지가 그 커밋 태그
    base=$PREV_RELEASE
    for i in $IMAGES; do prev_tags[$i]=$PREV_RELEASE; done
  fi
fi

if [ -n "$base" ] && ! git cat-file -e "$base^{commit}" 2> /dev/null; then
  echo "마지막 배포 커밋 $base 를 찾을 수 없다 → 전부 빌드" >&2
  base=""
fi

declare -A build=()
if [ "$FORCE_ALL" = true ] || [ -z "$base" ]; then
  for i in $IMAGES; do build[$i]=1; done
else
  # --no-renames: 이동한 파일은 원래 경로(삭제)와 새 경로(추가)를 모두 본다. 이미지 밖으로 옮겨도 원래 이미지를 다시 빌드한다
  changed=$(git diff --name-only --no-renames "$base" "$SHA")
  has() { echo "$changed" | grep -qE "$1"; }
  # 이 판단 자체가 바뀌면 전부
  if has '^(\.github/workflows/release\.yml|scripts/release-plan\.sh)$'; then
    for i in $IMAGES; do build[$i]=1; done
  fi
  # 백엔드 공통 입력: 공유 모듈·빌드 설정·Gradle·서비스 Dockerfile
  if has '^(libs/|build-logic/|gradle/|settings\.gradle\.kts$|build\.gradle\.kts$|gradle\.properties$|gradlew$|deploy/docker/service\.Dockerfile$)'; then
    for s in $BACKEND; do build[$s]=1; done
  fi
  for s in $BACKEND; do
    if has "^services/$s/"; then build[$s]=1; fi
  done
  # 화면 SPA 이미지(frontend/Dockerfile). 공용 패키지·workspace 설정이 바뀌면 둘 다, 앱 폴더면 그 앱만
  if has '^frontend/(packages/|nginx/|Dockerfile$|\.dockerignore$|package\.json$|pnpm-lock\.yaml$|pnpm-workspace\.yaml$)'; then build[web]=1; build[admin]=1; fi
  if has '^frontend/apps/web/'; then build[web]=1; fi
  if has '^frontend/apps/admin/'; then build[admin]=1; fi
  if has '^deploy/docker/connect\.Dockerfile$'; then build[connect]=1; fi
  # 이전 태그가 없는 이미지(새로 생긴 서비스 등)
  for i in $IMAGES; do
    if [ -z "${prev_tags[$i]:-}" ]; then build[$i]=1; fi
  done
fi

list=""
{
  echo "# release 워크플로가 쓴다 (release/eks 에만 있다, scripts/release-plan.sh). 손으로 고치지 않는다"
  echo "# 이미지마다 마지막으로 다시 빌드된 main 커밋. 이번에 안 바뀐 이미지는 이전 태그 그대로 → 그 Deployment 는 롤링하지 않는다"
  echo "releasedCommit: $SHA"
  echo "commerceValues:"
  echo "  image:"
  echo "    tags:"
  for i in $IMAGES; do
    if [ -n "${build[$i]:-}" ]; then
      tag=$SHA
      list="$list $i"
    else
      tag=${prev_tags[$i]}
    fi
    echo "      $i: $tag"
  done
} > "$OUT"
list=${list# }

echo "기준: ${base:-(없음)} → $SHA"
echo "빌드: ${list:-(없음)}"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "build=$list" >> "$GITHUB_OUTPUT"
fi
