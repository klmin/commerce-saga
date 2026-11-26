#!/usr/bin/env bash
# kind 의 commerce-saga 를 compose 때와 같은 로컬 포트로 연결한다 (scripts/smoke-*.sh 를 그대로 돌리려고)
#   auth 8086, product 8087, search 8089, order 8081, inventory 8082, point 8083, payment 8084, notification 8085, gateway 8000, 화면 SPA 5173(web)·5174(admin) (정적 파일만. 로그인은 edge 를 거친다)
#   관측성을 켰으면(observability.enabled) Grafana 3030, Tempo 3210, Loki 3110, Prometheus 9091, Alertmanager 9095 도
#
# 실행: scripts/k8s-port-forward.sh        (백그라운드로 띄우고 연결될 때까지 기다린다)
#       scripts/k8s-port-forward.sh stop   (끊기)
# compose 로 띄운 서비스가 같은 포트를 쓰고 있으면 먼저 내린다
set -euo pipefail

NAMESPACE=${NAMESPACE:-commerce}
PID_FILE=${PID_FILE:-${TMPDIR:-/tmp}/commerce-saga-port-forward.pids}
FORWARDS="auth-service:8086:8086 product-service:8087:8087 search-service:8089:8089 order-service:8081:8081 inventory-service:8082:8082 point-service:8083:8083
payment-service:8084:8084 notification-service:8085:8085 gateway:8000:8000 web:5173:80 admin:5174:80"
if kubectl -n "$NAMESPACE" get svc grafana > /dev/null 2>&1; then
  FORWARDS="$FORWARDS grafana:3030:3000 tempo:3210:3200 loki:3110:3100 prometheus:9091:9090 alertmanager:9095:9093"
fi

stop() {
  [ -f "$PID_FILE" ] || return 0
  while read -r pid; do kill "$pid" 2> /dev/null || true; done < "$PID_FILE"
  rm -f "$PID_FILE"
}

if [ "${1:-}" = stop ]; then
  stop
  exit 0
fi

stop
for f in $FORWARDS; do
  IFS=: read -r svc local remote <<< "$f"
  kubectl -n "$NAMESPACE" port-forward "svc/$svc" "$local:$remote" > /dev/null 2>&1 &
  echo $! >> "$PID_FILE"
done

for port in 8086 8087 8089 8081 8082 8083 8084 8085 8000; do
  for i in $(seq 1 30); do curl -sf "http://localhost:$port/actuator/health" > /dev/null && break; sleep 1; done
  curl -sf "http://localhost:$port/actuator/health" > /dev/null || { echo "FAIL: localhost:$port 연결 안 됨" >&2; exit 1; }
done
echo "OK: port-forward (끊기: $0 stop)"
