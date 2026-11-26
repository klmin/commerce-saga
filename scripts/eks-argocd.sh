#!/usr/bin/env bash
# 이미 있는 EKS 클러스터에 ArgoCD 를 설치하고 EKS 용 app-of-apps(deploy/argocd/eks/root.yaml)를 건다 (후속 3 K-4, deploy/README.md)
# 처음 한 번만 돌린다. 그 뒤로는 main 머지 → release 워크플로(GHCR push, release/eks 이동) → ArgoCD 가 알아서 sync
#
# 준비: kubectl 이 EKS 를 가리킨다 (aws eks update-kubeconfig --name <클러스터> --region <리전>), helm
#       release 워크플로가 한 번 돌아 release/eks 브랜치와 GHCR 이미지가 있다
# 실행: scripts/eks-argocd.sh
#
# sync 결과를 Discord 로 (ArgoCD Notifications, deploy/argocd/notifications.yaml): 웹훅 URL 을 환경변수로 주면 Secret 에 넣고 켠다.
#   URL 이 셸 기록에 남지 않게 입력창으로: read -rs ARGOCD_DISCORD_WEBHOOK && export ARGOCD_DISCORD_WEBHOOK && scripts/eks-argocd.sh
#   한 번 넣으면 Secret 이 남아 있으므로 다음 실행에서는 주지 않아도 켜진 채로 둔다
set -euo pipefail

ARGOCD_CHART_VERSION=${ARGOCD_CHART_VERSION:-10.18.0}
NAMESPACE=${NAMESPACE:-commerce}
TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-1800}

cd "$(dirname "$0")/.."

echo "== 대상 클러스터: $(kubectl config current-context)"
kubectl get nodes -o wide

if ! git ls-remote --exit-code --heads origin release/eks > /dev/null; then
  echo "FAIL: origin 에 release/eks 가 없다. main 에 머지해 release 워크플로를 먼저 돌린다 (또는 Actions 에서 수동 실행)" >&2
  exit 1
fi

kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f - > /dev/null
if [ -n "${ARGOCD_DISCORD_WEBHOOK:-}" ]; then
  # 값은 파일·명령 인자로 남기지 않고 stdin 으로 (kubectl 이 Secret 매니페스트를 만들어 바로 apply)
  kubectl -n argocd create secret generic argocd-notifications-secret \
    --from-file=discord-webhook-url=<(printf '%s' "$ARGOCD_DISCORD_WEBHOOK") --dry-run=client -o yaml \
    | kubectl apply -f - > /dev/null
fi
if [ -n "$(kubectl -n argocd get secret argocd-notifications-secret -o jsonpath='{.data.discord-webhook-url}' 2> /dev/null || true)" ]; then
  notify_args=(-f deploy/argocd/notifications.yaml)
  echo "== Discord 알림: 켬 (argocd-notifications-secret)"
else
  notify_args=(--set notifications.enabled=false)
  echo "== Discord 알림: 끔 (ARGOCD_DISCORD_WEBHOOK 없음)"
fi

echo "== ArgoCD (chart $ARGOCD_CHART_VERSION)"
# UI 는 밖으로 열지 않는다. kubectl port-forward 로 본다 (아래 안내)
helm upgrade --install argocd argo-cd --repo https://argoproj.github.io/argo-helm \
  --version "$ARGOCD_CHART_VERSION" -n argocd --create-namespace --wait --timeout 8m \
  --set dex.enabled=false "${notify_args[@]}" \
  --set 'configs.params.server\.insecure=true' > /dev/null

echo "== root Application (release/eks)"
kubectl apply -n argocd -f deploy/argocd/eks/root.yaml

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  all_ok=true
  line=""
  for app in commerce-saga-root strimzi envoy-gateway commerce-saga; do
    s=$(kubectl -n argocd get application "$app" -o jsonpath='{.status.sync.status} {.status.health.status}' 2> /dev/null || true)
    line="$line $app=[${s:-없음}]"
    [ "$s" = "Synced Healthy" ] || all_ok=false
  done
  $all_ok && break
  if [ "$(date +%s)" -gt "$deadline" ]; then
    echo "FAIL: ${TIMEOUT_SECONDS}초 안에 Synced·Healthy 가 아님:$line" >&2
    kubectl -n argocd get applications -o wide >&2 || true
    kubectl -n "$NAMESPACE" get pods >&2 || true
    exit 1
  fi
  echo "  기다리는 중:$line"
  sleep 20
done

kubectl -n "$NAMESPACE" get pods
lb=$(kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=commerce-edge \
  -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}' 2> /dev/null || true)
echo
echo "OK: 배포 완료 (revision $(kubectl -n argocd get application commerce-saga -o jsonpath='{.status.sync.revision}'))"
echo "  화면:   http://${lb:-<LB 주소가 아직 없음: kubectl -n envoy-gateway-system get svc>}  (DNS 전파에 몇 분 걸린다)"
echo "  ArgoCD: kubectl -n argocd port-forward svc/argocd-server 8443:80  → http://localhost:8443"
echo "          admin 비밀번호: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
