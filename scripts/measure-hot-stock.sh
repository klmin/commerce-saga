#!/usr/bin/env bash
# 시나리오 18. 인기 상품 한 행에 몰린 재고 예약의 처리량·락 경합 측정 (docs/scenarios/18-hot-stock-lock.md)
#
# 준비: 인프라(postgres, kafka)와 inventory-service 를 띄운다. 리스너 스레드 수는 SPRING_KAFKA_LISTENER_CONCURRENCY
#   (기본 1 = 인스턴스 하나. inventory.command 파티션이 3개라 3 이면 인스턴스 셋과 같은 병렬도)
# 실행:
#   scripts/measure-hot-stock.sh                      # 상품 하나에 몰림 (hot)
#   PRODUCTS="9001 9002 9003" scripts/measure-hot-stock.sh   # 세 상품에 고르게 (spread, 비교용)
#   INVENTORY_URLS="http://localhost:8082 http://localhost:8092 http://localhost:8093" scripts/measure-hot-stock.sh
#     # 인스턴스 셋 (SERVER_PORT 만 바꿔 같은 그룹으로 더 띄운 경우. 리스너 시간을 합쳐 본다)
#
# 측정하는 것
#   ReserveStock 커맨드 N 건을 inventory.command 에 한꺼번에 넣고(오케스트레이터·HTTP 없이 재고 단계만)
#   1. 처리량: 예약 기록(stock_reservations.created_at, 서버 시각)의 처음~끝 구간으로 나눈 건/초
#   2. 리스너 한 건 처리 시간: /actuator/prometheus 의 spring_kafka_listener 타이머(측정 전후 차이)의 평균
#   3. 락 대기: 처리 중 pg_stat_activity 를 0.05초마다 훑어 재고 행 락(wait_event_type = Lock)을 기다리던 연결 수
#
# 측정용 상품(9001~)은 재고 행이 없는 id 만 쓴다(이미 있으면 중단). 넉넉히 넣었다가 끝나면 이번 실행이 만든 행과 예약 기록만
# 지운다. 커맨드가 다 처리되기 전에 끝나면(타임아웃·중단) 남은 커맨드가 나중에 처리될 수 있어 기록을 남기고,
# 다 처리된 뒤 CLEANUP_RUN=<run> PRODUCTS="..." 로 정리한다. 응답(inventory.reply)은
# order-service 가 "Saga 없는 주문의 응답"으로 무시한다. 검증(PASS/FAIL)이 아니라 측정용
set -euo pipefail
# Git Bash 가 컨테이너 안 경로(/opt/kafka/...)를 Windows 경로로 바꾸지 않게
export MSYS_NO_PATHCONV=1

# 같은 컨슈머 그룹의 inventory-service 인스턴스들 (공백으로 구분)
INVENTORY_URLS=${INVENTORY_URLS:-http://localhost:8082}
COMPOSE_FILE=${COMPOSE_FILE:-infra/docker-compose.yml}
N=${N:-3000}
PRODUCTS=${PRODUCTS:-9001}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-300}
# 측정 주문 id 접두사 (정리할 때 이것으로 찾는다)
RUN=$(printf '%04x' $(( RANDOM % 65536 )))
PREFIX="eeeeeeee-$RUN"

tmp=$(mktemp -d)
sampler_pid=
dc() { docker compose -f "$COMPOSE_FILE" exec -T "$@"; }
psql_q() { dc -e PGPASSWORD=postgres postgres psql -U postgres -d inventory_db -v ON_ERROR_STOP=1 -tAc "$1"; }
stop_sampler() {
  [ -n "$sampler_pid" ] || return 0
  psql_q "select pg_cancel_backend(pid) from pg_stat_activity where application_name = 'hot-stock-sampler'" > /dev/null
  wait "$sampler_pid" 2> /dev/null || true
  sampler_pid=
}
# 측정용 재고는 available + reserved = STOCK_MARK 로 넣는다 (예약은 available → reserved 로 옮길 뿐이라 합이 유지된다).
# 정리할 때 이 합인 행만 지워, 다른 재고 행을 지우지 않는다
STOCK_MARK=100000000
purge() { # run products(쉼표) — 그 실행의 예약·멱등 기록과 측정용 재고 행을 지운다
  psql_q "
    delete from stock_reservation_lines where order_id::text like 'eeeeeeee-$1%';
    delete from stock_reservations where order_id::text like 'eeeeeeee-$1%';
    delete from processed_message where message_id::text like 'ffffffff-$1%';
    ${2:+delete from stocks where product_id in ($2) and available + reserved = $STOCK_MARK;}" > /dev/null
}
created_stocks=
published=
completed=
cleanup() {
  stop_sampler || true
  if [ -n "$published" ] && [ -z "$completed" ]; then
    # 처리되지 않은 커맨드가 Kafka 에 남아 있을 수 있다. 지금 재고를 지우면 나중에 처리될 때 unknown product 실패
    # 예약·멱등 기록·응답이 새로 생기므로, 실행 식별자와 재고를 남기고 다 처리된 뒤 정리하게 한다
    echo "측정 커맨드가 다 처리되지 않아 기록을 남겼다. 처리가 끝난 뒤(컨슈머 lag 0) 정리:" >&2
    echo "  CLEANUP_RUN=$RUN PRODUCTS=\"${PRODUCTS}\" $0" >&2
  else
    purge "$RUN" "$created_stocks" || true
  fi
  rm -rf "$tmp"
}
trap cleanup EXIT

# 앞선 실패 실행 정리 모드
if [ -n "${CLEANUP_RUN:-}" ]; then
  read -r -a products <<< "$PRODUCTS"
  ids=$(IFS=,; echo "${products[*]}")
  left=$(psql_q "select count(*) from stock_reservations where order_id::text like 'eeeeeeee-$CLEANUP_RUN%'")
  purge "$CLEANUP_RUN" "$ids"
  echo "run=$CLEANUP_RUN 정리: 예약 기록 ${left}건, 측정용 재고(${ids}) 삭제"
  trap - EXIT
  rm -rf "$tmp"
  exit 0
fi
calc() { awk "BEGIN{printf \"%.1f\", $1}"; }
listener() { # -> "count sum" (리스너 타이머 누적, 성공한 처리만. 인스턴스가 여럿이면 합친다)
  local url
  for url in $INVENTORY_URLS; do curl -sf "$url/actuator/prometheus"; done | awk '
    /^spring_kafka_listener_seconds_count\{/ && /error="none"/ { c += $NF }
    /^spring_kafka_listener_seconds_sum\{/ && /error="none"/ { s += $NF }
    END { printf "%d %.6f\n", c, s }'
}

read -r -a products <<< "$PRODUCTS"
echo "== 18. 재고 예약 경합 측정 (N=$N, products=${products[*]}, run=$RUN)"

# 측정용 재고 (끝나도 모자라지 않게). 이미 있는 재고 행(시드 상품 1~5 등)은 덮어쓰거나 지우지 않도록 중단한다
ids=$(IFS=,; echo "${products[*]}")
existing=$(psql_q "select string_agg(product_id::text, ',') from stocks where product_id in ($ids)")
if [ -n "$existing" ]; then
  echo "FAIL: 재고 행이 이미 있는 상품($existing)은 측정에 쓰지 않는다. 없는 상품 id(9001~ 등)를 PRODUCTS 로 준다" >&2
  exit 1
fi
values=$(for p in "${products[@]}"; do printf "(%d, %d, 0, now())," "$p" "$STOCK_MARK"; done)
psql_q "insert into stocks (product_id, available, reserved, updated_at) values ${values%,}" > /dev/null
created_stocks=$ids

# ReserveStock N 건 (키 = orderId, 헤더 id = 메시지 id, type). 상품은 차례로 돌려 고르게 나눈다
for i in $(seq 1 "$N"); do
  p=${products[$(( i % ${#products[@]} ))]}
  order=$(printf '%s-4000-8000-%012x' "$PREFIX" "$i")
  msg=$(printf 'ffffffff-%s-4000-8000-%012x' "$RUN" "$i")
  printf 'id:%s,type:ReserveStock\t%s\t{"orderId":"%s","lines":[{"productId":%d,"quantity":1}]}\n' \
    "$msg" "$order" "$order" "$p"
done > "$tmp/commands"

before=$(listener)

# 락 대기 샘플러: 한 연결에서 0.05초마다 재고 DB 의 락 대기 연결 수를 센다 (통계 스냅숏을 매번 비운다).
# 처리가 끝나면 application_name 으로 찾아 취소한다 (docker 클라이언트를 kill 해도 서버 쪽 루프는 멈추지 않는다)
dc -e PGPASSWORD=postgres -e PGAPPNAME=hot-stock-sampler postgres psql -U postgres -d postgres -tAq -c "
  do \$\$ begin
    for i in 1..$(( TIMEOUT_SECONDS * 20 )) loop
      perform pg_stat_clear_snapshot();
      raise notice 'S %', (select count(*) from pg_stat_activity
                           where datname = 'inventory_db' and wait_event_type = 'Lock');
      perform pg_sleep(0.05);
    end loop;
  end \$\$" 2> "$tmp/samples" &
sampler_pid=$!

t0=$(date +%s.%N)
published=1   # 발행 도중 실패해도 일부는 이미 나갔을 수 있다
dc kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic inventory.command \
  --reader-property parse.key=true --reader-property parse.headers=true < "$tmp/commands"
produced=$(calc "$(date +%s.%N) - $t0")

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  done_count=$(psql_q "select count(*) from stock_reservations where order_id::text like '$PREFIX%'")
  [ "$done_count" -ge "$N" ] && break
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "FAIL: ${TIMEOUT_SECONDS}초 안에 끝나지 않음 ($done_count/$N)" >&2
    exit 1
  fi
  sleep 0.5
done
completed=1   # 발행한 커맨드가 모두 예약 기록을 남김 = 남은 커맨드 없음
stop_sampler
after=$(listener)

echo "  커맨드 발행 ${produced}s (처리보다 충분히 빨라야 처리량이 리스너 쪽 값이다)"
psql_q "
  select format('  예약 %s건 (RESERVED %s)  처리 구간 %ss  처리량 %s건/s',
    count(*), count(*) filter (where status = 'RESERVED'),
    round(extract(epoch from max(created_at) - min(created_at))::numeric, 2),
    round(count(*) / nullif(extract(epoch from max(created_at) - min(created_at)), 0)::numeric, 1))
  from stock_reservations where order_id::text like '$PREFIX%'"
read -r c0 s0 <<< "$before"; read -r c1 s1 <<< "$after"
echo "  리스너 한 건 평균 $(calc "($s1 - $s0) * 1000 / ($c1 - $c0)") ms ($(( c1 - c0 ))건)"
# 처리 중 구간의 샘플만 (앞뒤로 처리 전·후 0 이 붙는다)
grep -o 'S [0-9]*' "$tmp/samples" | awk '{print $2}' > "$tmp/s"
awk '{ n++; if ($1 > 0) w++; t += $1; if ($1 > m) m = $1 }
     END { printf "  락 대기: 샘플 %d개 중 %d개(%.0f%%)에서 대기 연결 있음, 평균 %.2f, 최대 %d\n", n, w, n ? w * 100 / n : 0, n ? t / n : 0, m }' "$tmp/s"
# 측정 예약 기록·멱등 기록·측정용 재고 정리는 cleanup (EXIT trap)
