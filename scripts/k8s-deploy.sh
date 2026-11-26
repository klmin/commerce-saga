#!/usr/bin/env bash
# kind 클러스터에 commerce-saga 를 올린다 (후속 3 K-1, deploy/README.md)
#
# 준비: kind·kubectl·helm·docker, 클러스터 (kind create cluster --name commerce-saga), bootJar·화면 이미지 빌드 가능
# 실행: scripts/k8s-deploy.sh [helm 추가 인자...]
#   예) scripts/k8s-deploy.sh --set services.gateway.env.GATEWAY_USER_RATE=5
#
# 하는 일
#   1. 서비스 8개 이미지(deploy/docker/service.Dockerfile) + 화면 SPA(web·admin) + Kafka Connect(Debezium) 이미지를 빌드해 kind 노드에 넣는다
#   2. Strimzi 오퍼레이터를 같은 namespace 에 설치한다 (그 namespace 의 Kafka CR 만 본다)
#   3. 차트(deploy/helm/commerce-saga)를 설치하고 Kafka·토픽·Deployment 가 준비될 때까지 기다린다
set -euo pipefail

KIND_CLUSTER=${KIND_CLUSTER:-commerce-saga}
NAMESPACE=${NAMESPACE:-commerce}
RELEASE=${RELEASE:-commerce}
SKIP_BUILD=${SKIP_BUILD:-false}
# 빌드할 때마다 새 태그다. 태그가 같으면 Pod 템플릿이 안 바뀌어 다시 배포해도 옛 이미지로 남는다
# (빌드 없이 다시 올릴 때는 TAG 로 이미 넣은 태그를 준다)
if [ -z "${TAG:-}" ]; then
  if [ "$SKIP_BUILD" = true ]; then
    echo "SKIP_BUILD=true 면 TAG 로 kind 에 넣어 둔 이미지 태그를 준다" >&2
    exit 1
  fi
  TAG="dev-$(date +%Y%m%d%H%M%S)"
fi
STRIMZI_VERSION=${STRIMZI_VERSION:-1.3.1}
SERVICES="auth-service product-service search-service order-service inventory-service point-service payment-service notification-service gateway"

cd "$(dirname "$0")/.."

if [ "$SKIP_BUILD" != true ]; then
  echo "== 이미지 빌드"
  ./gradlew -q $(for s in $SERVICES; do printf ':services:%s:bootJar ' "$s"; done)
  for s in $SERVICES; do
    docker build -q -f deploy/docker/service.Dockerfile \
      --build-arg JAR="services/$s/build/libs/$s-0.0.1-SNAPSHOT.jar" -t "commerce-saga/$s:$TAG" . > /dev/null
  done
  # 화면 SPA (nginx, ADR 0017)
  for app in web admin; do
    docker build -q --build-arg APP=$app -t "commerce-saga/$app:$TAG" frontend > /dev/null
  done
  # Debezium Postgres 플러그인을 얹은 Strimzi Kafka Connect (K-2)
  docker build -q -f deploy/docker/connect.Dockerfile -t "commerce-saga/connect:$TAG" deploy/docker > /dev/null
fi

echo "== kind 노드에 이미지 넣기"
for s in $SERVICES web admin connect; do
  kind load docker-image "commerce-saga/$s:$TAG" --name "$KIND_CLUSTER" > /dev/null
done

# ArgoCD 로 배포할 때(scripts/k8s-argocd.sh)는 이미지까지만. 나머지는 ArgoCD 가 Git 에서 맞춘다
if [ "${IMAGES_ONLY:-false}" = true ]; then
  exit 0
fi

echo "== Strimzi $STRIMZI_VERSION"
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - > /dev/null
helm upgrade --install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator \
  --version "$STRIMZI_VERSION" -n "$NAMESPACE" --wait --timeout 5m > /dev/null

echo "== commerce-saga 차트"
helm upgrade --install "$RELEASE" deploy/helm/commerce-saga -n "$NAMESPACE" \
  --set image.tag="$TAG" "$@" > /dev/null

kubectl -n "$NAMESPACE" wait kafka/"$RELEASE" --for=condition=Ready --timeout=10m
kubectl -n "$NAMESPACE" wait kafkatopic --all --for=condition=Ready --timeout=5m
kubectl -n "$NAMESPACE" rollout status statefulset/postgres --timeout=5m
# 상품 검색 저장소 (ADR 0010). 이미지가 커서 처음 받을 때 오래 걸린다
kubectl -n "$NAMESPACE" rollout status statefulset/elasticsearch --timeout=8m
for d in redis $SERVICES web admin; do
  kubectl -n "$NAMESPACE" rollout status deployment/"$d" --timeout=8m
done
# Debezium (debezium.enabled). 커넥터는 서비스의 Flyway 가 outbox 테이블을 만든 뒤에야 Ready 가 된다
if kubectl -n "$NAMESPACE" get kafkaconnect "$RELEASE" > /dev/null 2>&1; then
  kubectl -n "$NAMESPACE" wait kafkaconnect/"$RELEASE" --for=condition=Ready --timeout=8m
  kubectl -n "$NAMESPACE" wait kafkaconnector --all --for=condition=Ready --timeout=5m
fi
# 관측성 (observability.enabled)
if kubectl -n "$NAMESPACE" get deployment alloy > /dev/null 2>&1; then
  for d in alloy tempo loki prometheus kafka-exporter grafana; do
    kubectl -n "$NAMESPACE" rollout status deployment/"$d" --timeout=5m
  done
fi
kubectl -n "$NAMESPACE" get pods
echo "OK: 배포 완료. scripts/k8s-port-forward.sh 로 로컬 포트에 연결한다"
