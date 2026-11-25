#!/bin/sh
# Alertmanager 기동 (ADR 0013 "경보 전달"). Discord 웹훅 URL 이 있으면 그대로, 없으면 receiver 를 "null" 로 바꿔 띄운다
#   URL 출처(앞이 우선): 환경 변수 DISCORD_WEBHOOK_URL(compose) → 파일 DISCORD_WEBHOOK_URL_FILE(k8s Secret)
# URL 은 /alertmanager(데이터 폴더, 컨테이너 안에서만 보임)의 파일로 옮겨 설정의 webhook_url_file 이 읽는다. 로그에 URL 을 찍지 않는다
set -eu
config=/etc/alertmanager/alertmanager.yml
data=/alertmanager
url_file="$data/discord-webhook-url"

rm -f "$url_file"
if [ -n "${DISCORD_WEBHOOK_URL:-}" ]; then
  printf '%s' "$DISCORD_WEBHOOK_URL" > "$url_file"
elif [ -n "${DISCORD_WEBHOOK_URL_FILE:-}" ] && [ -s "$DISCORD_WEBHOOK_URL_FILE" ]; then
  tr -d '\r\n' < "$DISCORD_WEBHOOK_URL_FILE" > "$url_file"
fi

if [ -s "$url_file" ]; then
  echo "alertmanager: Discord 웹훅으로 보낸다"
  cp "$config" "$data/alertmanager.yml"
else
  echo "alertmanager: Discord 웹훅 URL 이 없어 null receiver 로 띄운다 (경보는 UI·API 에서만 보인다)"
  sed 's/^\( *\)receiver: discord$/\1receiver: "null"/' "$config" > "$data/alertmanager.yml"
fi

# 한 개만 띄우므로 gossip 클러스터는 끈다
exec /bin/alertmanager --config.file="$data/alertmanager.yml" --storage.path="$data" --cluster.listen-address= "$@"
