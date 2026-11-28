#!/usr/bin/env bash
# 상품 행 전부를 product.cdc.products 로 다시 보낸다 (후속 9-3, ADR 0021, docs/scenarios/16-product-cdc.md)
#
# 언제: 커넥터의 column.include.list 에 열을 더한 뒤(예: 9-3 의 category_code·image_key). 커넥터는 설정을 바꾼 뒤의
#   변경부터 새 열을 싣는다. 그 전에 색인된 상품은 새 열 없이 남아 있어(카테고리 필터에 안 걸림) 한 번 다시 보낸다.
#   처음 띄우는 환경(커넥터를 새로 등록)은 스냅샷이 새 열까지 보내므로 필요 없다
# 어떻게: product_db 의 products 행을 값이 그대로인 UPDATE 로 한 번 건드린다. 행마다 WAL 에 변경이 남고 Debezium 이
#   변경 후 행(새 열 포함)을 보낸다. __lsn 이 지금 것이라 검색 색인은 옛 문서를 덮어쓴다. 값·updated_at 은 바뀌지 않는다
#   (Debezium 증분 스냅샷 signal 은 signal 테이블·publication 변경이 필요해 쓰지 않았다)
# 준비: 커넥터 설정 갱신 (compose: scripts/register-connectors.sh, k8s: helm upgrade 로 KafkaConnector 갱신)
# 실행: scripts/resend-product-cdc.sh
#       k8s: PSQL_EXEC="kubectl -n commerce exec -i postgres-0 -- psql -U postgres" scripts/resend-product-cdc.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
PSQL_EXEC=${PSQL_EXEC:-docker compose -f $COMPOSE_FILE exec -T -e PGPASSWORD=postgres postgres psql -U postgres}

# shellcheck disable=SC2086
count=$($PSQL_EXEC -d product_db -v ON_ERROR_STOP=1 -tAc "
  with touched as (update products set updated_at = updated_at returning 1)
  select count(*) from touched" | tr -d '\r' | tail -1)
echo "product.cdc.products 로 다시 보냄: 상품 ${count}건 (검색 반영은 몇 초 뒤)"
