#!/bin/sh
# 시작할 때 index.html 의 <base href> 를 BASE_PATH 로 바꾼다 (설계 문서 7-4).
# 호스트 방식은 / (기본), 경로 방식은 /admin/·/auth/. 브라우저는 <base> 의 마지막 / 뒤를 버리므로 / 로 끝나야 한다
set -eu

BASE_PATH="${BASE_PATH:-/}"
INDEX=/usr/share/nginx/html/index.html

case "$BASE_PATH" in
  /|/*/) ;;
  *) echo "BASE_PATH 는 / 로 시작하고 / 로 끝나야 한다: '$BASE_PATH'" >&2; exit 1 ;;
esac
# 경로에 쓸 글자만 (index.html 에 그대로 들어간다)
case "$BASE_PATH" in
  *[!A-Za-z0-9/_.-]*|*..*) echo "BASE_PATH 에 쓸 수 없는 글자가 있다: '$BASE_PATH'" >&2; exit 1 ;;
esac

# html 폴더는 root 소유라 sed -i 가 임시 파일을 못 만든다 → /tmp 에서 바꿔 내용만 덮어쓴다 (파일은 nginx 사용자 소유)
sed "s|<base href=\"[^\"]*\" */\?>|<base href=\"$BASE_PATH\" />|" "$INDEX" > /tmp/index.html
cat /tmp/index.html > "$INDEX"
rm -f /tmp/index.html
grep -q "<base href=\"$BASE_PATH\"" "$INDEX" || { echo "index.html 에 <base href> 를 넣지 못했다" >&2; exit 1; }
echo "base href = $BASE_PATH"
