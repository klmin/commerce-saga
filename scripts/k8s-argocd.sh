#!/usr/bin/env bash
# kind 클러스터에 ArgoCD 를 설치하고 app-of-apps(deploy/argocd/root.yaml)로 commerce-saga 를 올린다 (후속 3 K-3, deploy/README.md)
#
# 준비: kind·kubectl·helm·docker, 클러스터 (kind create cluster --name commerce-saga)
# 실행: REVISION=<커밋 또는 브랜치> scripts/k8s-argocd.sh
#   REVISION 은 ArgoCD 가 GitHub 에서 가져올 커밋이다. push 된 커밋이어야 한다 (기본 main)
#
# 하는 일
#   1. 이미지 빌드 → kind 노드에 넣기 (k8s-deploy.sh 의 앞부분, 레지스트리가 없어서)
#   2. ArgoCD 설치 (Helm)
#   3. root Application 하나만 apply → ArgoCD 가 Strimzi·Envoy Gateway·commerce-saga 를 Git 에서 가져와 sync
#   4. 모든 Application 이 Synced·Healthy 가 되고 Kafka 커넥터가 Ready 일 때까지 기다린다
set -euo pipefail

REVISION=${REVISION:-main}
ARGOCD_CHART_VERSION=${ARGOCD_CHART_VERSION:-10.18.0}
NAMESPACE=${NAMESPACE:-commerce}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-1200}

cd "$(dirname "$0")/.."

IMAGES_ONLY=true bash scripts/k8s-deploy.sh

echo "== ArgoCD (chart $ARGOCD_CHART_VERSION)"
helm upgrade --install argocd argo-cd --repo https://argoproj.github.io/argo-helm \
  --version "$ARGOCD_CHART_VERSION" -n argocd --create-namespace --wait --timeout 8m \
  --set dex.enabled=false --set notifications.enabled=false \
  --set 'configs.params.server\.insecure=true' > /dev/null

echo "== root Application (revision $REVISION)"
sed -e "s|targetRevision: main|targetRevision: $REVISION|" -e "s|value: main|value: $REVISION|" \
  deploy/argocd/root.yaml | kubectl apply -f -

app_state() { # name -> "<sync> <health>"
  kubectl -n argocd get application "$1" -o jsonpath='{.status.sync.status} {.status.health.status}' 2> /dev/null || true
}

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  all_ok=true
  line=""
  for app in commerce-saga-root strimzi envoy-gateway commerce-saga; do
    s=$(app_state "$app")
    line="$line $app=[${s:-없음}]"
    [ "$s" = "Synced Healthy" ] || all_ok=false
  done
  $all_ok && break
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "FAIL: ${TIMEOUT_SECONDS}초 안에 Synced·Healthy 가 아님:$line" >&2
    kubectl -n argocd get applications -o wide >&2 || true
    kubectl -n argocd get application commerce-saga -o jsonpath='{.status.conditions}{"\n"}{.status.operationState.message}{"\n"}' >&2 || true
    exit 1
  fi
  echo "  기다리는 중:$line"
  sleep 15
done
echo "  모두 Synced·Healthy:$line"

# Application 이 Healthy 여도 커넥터는 서비스 Flyway 가 outbox 를 만든 뒤에 Ready 가 된다
if kubectl -n "$NAMESPACE" get kafkaconnect > /dev/null 2>&1; then
  kubectl -n "$NAMESPACE" wait kafkaconnector --all --for=condition=Ready --timeout=5m
fi
kubectl -n "$NAMESPACE" get pods
echo "OK: ArgoCD 로 배포 완료"
