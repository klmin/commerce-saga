#!/usr/bin/env bash
# 시나리오 17. 상품 검색: 가격 변경·판매 중지가 몇 초 안에 검색에 반영되고, ES 가 멈춰도 검색만 503 (docs/scenarios/17-product-search.md, ADR 0010)
#
# 준비: 인프라(elasticsearch, connect 포함), product-service, search-service(8089), gateway(8000), product-cdc 커넥터 등록
# 실행: scripts/smoke-search.sh
#       k8s: KAFKA_EXEC="kubectl -n commerce exec -i commerce-dual-role-0 --" KAFKA_BOOTSTRAP=localhost:9092 SKIP_OUTAGE=true scripts/smoke-search.sh
#
# 확인하는 것
#   17a. 처음 적재(스냅샷)된 시드 상품이 한글 부분 검색어로 나온다
#   17b. 관리자가 가격을 바꾸면 몇 초 안에 검색 결과 가격이 바뀐다
#   17c. 판매 중지하면 기본 검색(판매 중만)에서 빠지고, onSaleOnly=false 면 보인다
#   17d. 늦게 온 옛 변경(작은 __lsn)을 토픽에 넣어도 검색 결과가 되돌아가지 않는다
#   17e. (compose) Elasticsearch 를 멈추면 검색만 503, 상품 목록·가격 변경은 그대로. 다시 띄우면 멈춘 동안의 변경을 따라잡는다
#   17f. 카테고리 필터(category=digital 이면 나오고 kitchen 이면 안 나옴)와 결과의 이미지 경로 (ADR 0021)
# 끝나면 가격·판매 상태를 원래대로 돌려놓는다
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID·가격 (product_id, seed_price)
source "$(dirname "$0")/lib/products.sh"
export MSYS_NO_PATHCONV=1

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
KAFKA_EXEC=${KAFKA_EXEC:-docker compose -f $COMPOSE_FILE exec -T kafka}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:29092}
SKIP_OUTAGE=${SKIP_OUTAGE:-false}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-60}
PRODUCT_ID=$PRODUCT_MOUSE   # 무선 마우스 (시드 2)

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
urlencode() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1" 2>/dev/null \
  || node -e 'console.log(encodeURIComponent(process.argv[1]))' "$1"; }
admin_put() { # path json
  curl -sf -X PUT "$GATEWAY_URL/api/admin/products/$PRODUCT_ID$1" -H "$(auth "$ADMIN")" \
    -H 'Content-Type: application/json' -d "$2" > /dev/null
}
search() { # 검색어 [추가 쿼리]
  curl -s -w '\n%{http_code}' "$GATEWAY_URL/api/search/products?q=$(urlencode "$1")${2:-}"
}
# 검색 결과에서 상품 id 의 가격 (없으면 빈 줄)
price_in() { # 검색어 [추가 쿼리]
  local out; out=$(search "$@")
  [ "$(tail -n1 <<< "$out")" = 200 ] || return 0
  head -n -1 <<< "$out" | grep -oE "\{\"id\":\"$PRODUCT_ID\",[^}]*\}" | field price || true
}
wait_until() { # 설명 명령...
  local what=$1; shift
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
  until "$@"; do
    [ "$(date +%s)" -gt "$deadline" ] && fail "${TIMEOUT_SECONDS}초 안에 $what"
    sleep 1
  done
}

original=$(curl -sf "$GATEWAY_URL/api/products/$PRODUCT_ID" | field price)
name=$(curl -sf "$GATEWAY_URL/api/products/$PRODUCT_ID" | field name)
[ -n "$original" ] || fail "상품 $PRODUCT_ID 조회 실패 (product-service·Gateway 확인)"
restore() {
  admin_put /price "{\"price\":$original}" || true
  admin_put /sale '{"onSale":true}' || true
  [ "$SKIP_OUTAGE" = true ] || docker compose -f "$COMPOSE_FILE" start elasticsearch > /dev/null 2>&1 || true
}
trap restore EXIT

echo "== 17a. 처음 적재된 시드 상품을 부분 검색어로"
has_seed() { [ "$(price_in "마우")" = "$original" ]; }
wait_until "'마우' 검색에 상품 $PRODUCT_ID($name) 가 나오지 않음 (search-service·커넥터 확인)" has_seed
ok "'마우' → $name $original 원"

echo "== 17b. 가격 변경 → 검색 반영"
changed=$(( original + 500 ))
start=$(date +%s)
admin_put /price "{\"price\":$changed}"
reflected() { [ "$(price_in "마우")" = "$changed" ]; }
wait_until "검색 가격이 $changed 로 바뀌지 않음" reflected
ok "$((  $(date +%s) - start ))초 안에 검색 가격 $original → $changed"

echo "== 17c. 판매 중지 → 기본 검색에서 빠짐"
admin_put /sale '{"onSale":false}'
hidden() { [ -z "$(price_in "마우")" ] && [ "$(price_in "마우" "&onSaleOnly=false")" = "$changed" ]; }
wait_until "판매 중지가 검색에 반영되지 않음" hidden
ok "기본 검색에서 빠지고 onSaleOnly=false 로는 보임"
admin_put /sale '{"onSale":true}'
wait_until "판매 재개가 반영되지 않음" reflected

echo "== 17d. 늦게 온 옛 변경은 무시"
old_row="{\"id\":\"$PRODUCT_ID\",\"name\":\"$name\",\"description\":\"\",\"price\":1,\"on_sale\":true,\"updated_at\":\"2020-01-01T00:00:00Z\"}"
# shellcheck disable=SC2086
printf '__lsn:1\t%s\t%s\n' "$PRODUCT_ID" "$old_row" | $KAFKA_EXEC /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server "$KAFKA_BOOTSTRAP" --topic product.cdc.products \
  --reader-property parse.key=true --reader-property parse.headers=true > /dev/null
# 다음 변경이 반영될 때까지 옛 변경(가격 1)이 끼어들지 않는다
newer=$(( changed + 1 ))
admin_put /price "{\"price\":$newer}"
newest() { [ "$(price_in "마우")" = "$newer" ]; }
wait_until "다음 변경($newer)이 반영되지 않음" newest
sleep 2
[ "$(price_in "마우")" = "$newer" ] || fail "옛 변경(가격 1)으로 되돌아감"
ok "옛 변경(__lsn=1, 가격 1) 무시, 가격 $newer 유지"

if [ "$SKIP_OUTAGE" = true ]; then
  echo "== 17e. 건너뜀 (SKIP_OUTAGE)"
else
  echo "== 17e. Elasticsearch 중단 → 검색만 503, 재개 후 따라잡음"
  docker compose -f "$COMPOSE_FILE" stop elasticsearch > /dev/null
  code=$(search "마우" | tail -n1)
  [ "$code" = 503 ] || fail "ES 중단 중 검색 → $code (기대 503)"
  curl -sf "$GATEWAY_URL/api/products?page=0&size=5" > /dev/null || fail "ES 중단 중 상품 목록 실패"
  during=$(( newer + 1 ))
  admin_put /price "{\"price\":$during}" || fail "ES 중단 중 가격 변경 실패"
  ok "검색 503, 상품 목록·가격 변경은 정상"
  docker compose -f "$COMPOSE_FILE" start elasticsearch > /dev/null
  caught_up() { [ "$(price_in "마우")" = "$during" ]; }
  # ES 기동(약 20초) + 소비자 재시도 간격(최대 30초)
  TIMEOUT_SECONDS=120
  wait_until "ES 재개 뒤 멈춘 동안의 변경($during)을 따라잡지 못함" caught_up
  ok "재개 뒤 가격 $during 반영 (색인 소비자가 제자리 재시도로 기다렸다가 처리)"
fi

echo "== 17f. 카테고리 필터·이미지 경로"
in_category() { [ -n "$(price_in "마우" "&category=$1")" ]; }
wait_until "category=digital 검색에 상품 $PRODUCT_ID 가 나오지 않음 (커넥터 열·색인 매핑 확인)" in_category digital
in_category kitchen && fail "category=kitchen 검색에 상품 $PRODUCT_ID(digital) 가 나옴"
image=$(search "마우" | grep -oE "\{\"id\":\"$PRODUCT_ID\",[^}]*\}" | field imagePath)
[ "$image" = "/products/$PRODUCT_ID/images/seed.svg" ] || fail "imagePath 가 시드 이미지 경로가 아님: $image"
code=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL/api$image")
[ "$code" = 200 ] || fail "이미지 $image 읽기 → $code (기대 200, product-service·RustFS 확인)"
ok "category=digital 에만 나오고, 이미지 $image 읽기 200"

echo "시나리오 17 통과"
