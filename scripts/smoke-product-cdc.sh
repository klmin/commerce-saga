#!/usr/bin/env bash
# 시나리오 16. 상품 테이블 CDC: products 의 변경이 product.cdc.products 로 나간다 (docs/scenarios/16-product-cdc.md, ADR 0010)
#
# 준비: 인프라(connect 포함), product-service, gateway(8000), product-cdc 커넥터 등록
#       compose: CONNECTORS=product-cdc scripts/register-connectors.sh (debezium 모드면 전부 등록에 포함)
#       k8s:     KafkaConnector product-cdc (debezium.enabled)
# 실행: scripts/smoke-product-cdc.sh
#       k8s: KAFKA_EXEC="kubectl -n commerce exec -i commerce-dual-role-0 --" KAFKA_BOOTSTRAP=localhost:9092 scripts/smoke-product-cdc.sh
#
# 확인하는 것
#   16a. 처음 적재(스냅샷): 시드 상품 1(무선 키보드)의 행이 키 = 그 상품 id(UUID 문자열)로 있다
#   16b. 관리자가 상품 가격을 바꾸면 그 변경이 키 = 상품 id, 변경 후 행(평평한 JSON)으로 나간다
#        검색에 필요한 열만(id, name, description, price, on_sale, updated_at), 헤더에 원본 __lsn·__source_ts_ms
#   16c. 같은 상품의 다음 변경은 __lsn 이 더 크다 (검색 쪽 외부 버전으로 쓴다)
# 끝나면 가격을 원래대로 돌려놓는다
set -euo pipefail
source "$(dirname "$0")/lib/auth.sh"
# 시드 상품 고정 UUID·가격 (product_id, seed_price)
source "$(dirname "$0")/lib/products.sh"
# Windows Git Bash 가 컨테이너 안 경로(/opt/kafka/...)를 윈도 경로로 바꾸지 않게 한다 (리눅스에서는 영향 없음)
export MSYS_NO_PATHCONV=1

GATEWAY_URL=${GATEWAY_URL:-http://localhost:8000}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
KAFKA_EXEC=${KAFKA_EXEC:-docker compose -f $COMPOSE_FILE exec -T kafka}
KAFKA_BOOTSTRAP=${KAFKA_BOOTSTRAP:-localhost:29092}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-30}
TOPIC=product.cdc.products
# 상품 id (UUID). 시드 번호 1~5 로 줘도 된다
PRODUCT_ID=$(product_id "${PRODUCT_ID:-4}")

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "  ok  $*"; }
field() { sed -nE "s/.*\"$1\":\"?([^\",}]*)\"?.*/\1/p" | head -1; }
set_price() {
  curl -sf -X PUT "$GATEWAY_URL/api/admin/products/$PRODUCT_ID/price" -H "$(auth "$ADMIN")" \
    -H 'Content-Type: application/json' -d "{\"price\":$1}" > /dev/null
}

# 토픽 전체를 "헤더<TAB>키<TAB>값" 줄로 (처음부터, 3초 동안 새 메시지가 없으면 끝)
records() {
  # shellcheck disable=SC2086
  $KAFKA_EXEC /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$TOPIC" \
    --from-beginning --timeout-ms 3000 --formatter-property print.key=true --formatter-property print.headers=true \
    2> /dev/null | tr -d '\r' || true
}
# 키가 $1 이고 가격이 $2 인 마지막 메시지 (없으면 빈 줄)
record_with_price() {
  records | awk -F'\t' -v k="$1" '$2 == k' | grep "\"price\":$2[,}]" | tail -1 || true
}
wait_record() { # key price
  local deadline=$(( $(date +%s) + TIMEOUT_SECONDS )) r
  while :; do
    r=$(record_with_price "$1" "$2")
    [ -n "$r" ] && { echo "$r"; return 0; }
    [ "$(date +%s)" -gt "$deadline" ] && return 1
    sleep 1
  done
}
lsn_of() { grep -oE '__lsn:[0-9]+' | head -1 | cut -d: -f2; }

original=$(curl -sf "$GATEWAY_URL/api/products/$PRODUCT_ID" | field price)
[ -n "$original" ] || fail "상품 $PRODUCT_ID 조회 실패 (product-service·Gateway 확인)"
trap 'set_price "$original" || true' EXIT

echo "== 16a. 처음 적재: 시드 상품 1 이 키 $PRODUCT_KEYBOARD 로 있다"
records | awk -F'\t' -v k="$PRODUCT_KEYBOARD" '$2 == k' | grep -q "\"id\":\"$PRODUCT_KEYBOARD\"" || fail "$TOPIC 에 상품 1 이 없음 (커넥터 스냅샷 확인)"
ok "상품 1 스냅샷 있음"

echo "== 16b. 가격 변경 → 변경 후 행이 키 = 상품 id 로"
first=$(( original + 100 ))
set_price "$first"
r1=$(wait_record "$PRODUCT_ID" "$first") || fail "${TIMEOUT_SECONDS}초 안에 가격 $first 변경이 $TOPIC 에 오지 않음"
value=$(cut -f3 <<< "$r1")
for col in id name description price on_sale updated_at; do
  grep -q "\"$col\":" <<< "$value" || fail "값에 $col 이 없음: $value"
done
grep -q '"created_at"' <<< "$value" && fail "포함하지 않은 열(created_at)이 나감: $value"
grep -q '"before"\|"after"\|"op"' <<< "$value" && fail "변경 후 행이 아니라 Debezium 봉투가 나감: $value"
grep -q '__source_ts_ms:[0-9]' <<< "$r1" || fail "헤더에 __source_ts_ms 가 없음: $r1"
lsn1=$(lsn_of <<< "$r1")
[ -n "$lsn1" ] || fail "헤더에 __lsn 이 없음: $r1"
ok "키 $PRODUCT_ID, 가격 $first, 열 6개만, 헤더 __lsn=$lsn1"

echo "== 16c. 다음 변경은 __lsn 이 더 크다"
second=$(( original + 200 ))
set_price "$second"
r2=$(wait_record "$PRODUCT_ID" "$second") || fail "${TIMEOUT_SECONDS}초 안에 가격 $second 변경이 오지 않음"
lsn2=$(lsn_of <<< "$r2")
[ -n "$lsn2" ] && [ "$lsn2" -gt "$lsn1" ] || fail "__lsn 이 늘지 않음: $lsn1 → $lsn2"
ok "__lsn $lsn1 → $lsn2"

echo "시나리오 16 통과"
