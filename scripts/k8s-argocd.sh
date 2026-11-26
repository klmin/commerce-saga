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

# 빌드마다 새 태그로 넣고 같은 값을 root → 자식 Application 의 image.tag 로 넘긴다 (같은 태그면 다시 돌려도 Pod 가 옛 이미지로 남는다)
TAG=${TAG:-dev-$(date +%Y%m%d%H%M%S)}
TAG=$TAG IMAGES_ONLY=true bash scripts/k8s-deploy.sh

# ARGOCD_NOTIFY=true: EKS 와 같은 알림 설정(deploy/argocd/notifications.yaml)으로 켠다. 받을 곳은 Secret
# argocd-notifications-secret 의 discord-webhook-url 에 미리 넣어 둔다 (CI 는 클러스터 안 수신 Pod)
if [ "${ARGOCD_NOTIFY:-false}" = true ]; then
  notify_args=(-f deploy/argocd/notifications.yaml)
else
  notify_args=(--set notifications.enabled=false)
fi

echo "== ArgoCD (chart $ARGOCD_CHART_VERSION, 알림 ${ARGOCD_NOTIFY:-false})"
helm upgrade --install argocd argo-cd --repo https://argoproj.github.io/argo-helm \
  --version "$ARGOCD_CHART_VERSION" -n argocd --create-namespace --wait --timeout 8m \
  --set dex.enabled=false "${notify_args[@]}" \
  --set 'configs.params.server\.insecure=true' > /dev/null

echo "== root Application (revision $REVISION, image.tag $TAG, namespace $NAMESPACE)"
sed -e "s|targetRevision: main|targetRevision: $REVISION|" -e "s|value: main|value: $REVISION|" \
  -e "s|value: dev|value: $TAG|" -e "s|value: commerce|value: $NAMESPACE|" \
  deploy/argocd/root.yaml | kubectl apply -f -

app_state() { # name -> "<sync> <health>"
  kubectl -n argocd get application "$1" -o jsonpath='{.status.sync.status} {.status.health.status}' 2> /dev/null || true
}

# 다시 돌릴 때는 apply 직후에도 앞 배포의 Synced·Healthy 가 남아 있다. 이번 값이 자식까지 내려가고 그 커밋으로 sync 됐는지 본다
this_deploy_applied() {
  local tag ns rev
  tag=$(kubectl -n argocd get application commerce-saga -o jsonpath='{.spec.source.helm.valuesObject.image.tag}' 2> /dev/null || true)
  ns=$(kubectl -n argocd get application commerce-saga -o jsonpath='{.spec.destination.namespace}' 2> /dev/null || true)
  [ "$tag" = "$TAG" ] && [ "$ns" = "$NAMESPACE" ] || return 1
  # REVISION 이 커밋 해시면 그 커밋으로 sync 했는지까지 (브랜치 이름이면 해시를 몰라 건너뛴다)
  if [[ "$REVISION" =~ ^[0-9a-f]{7,40}$ ]]; then
    rev=$(kubectl -n argocd get application commerce-saga -o jsonpath='{.status.sync.revision}' 2> /dev/null || true)
    [[ "$rev" == "$REVISION"* ]] || return 1
  fi
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
  this_deploy_applied || { all_ok=false; line="$line (이번 revision·태그 반영 전)"; }
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
# 이번 태그의 Pod 로 다 바뀌었는지 (Healthy 는 옛 Pod 로도 날 수 있다)
for d in auth-service product-service search-service order-service inventory-service point-service payment-service notification-service gateway frontend; do
  image=$(kubectl -n "$NAMESPACE" get deployment "$d" -o jsonpath='{.spec.template.spec.containers[0].image}')
  [[ "$image" == *":$TAG" ]] || { echo "FAIL: $d 이미지가 $image (기대 태그 $TAG)" >&2; exit 1; }
  kubectl -n "$NAMESPACE" rollout status deployment/"$d" --timeout=5m > /dev/null
done
kubectl -n "$NAMESPACE" get pods
echo "OK: ArgoCD 로 배포 완료"
