#!/usr/bin/env bash
# kind 클러스터에 commerce-saga 를 올린다 (후속 3 K-1, deploy/README.md)
#
# 준비: kind·kubectl·helm·docker, 클러스터 (kind create cluster --name commerce-saga), bootJar·frontend 이미지 빌드 가능
# 실행: scripts/k8s-deploy.sh [helm 추가 인자...]
#   예) scripts/k8s-deploy.sh --set services.gateway.env.GATEWAY_USER_RATE=5
#
# 하는 일
#   1. 서비스 7개 이미지(deploy/docker/service.Dockerfile) + frontend 이미지를 빌드해 kind 노드에 넣는다
#   2. Strimzi 오퍼레이터를 같은 namespace 에 설치한다 (그 namespace 의 Kafka CR 만 본다)
#   3. 차트(deploy/helm/commerce-saga)를 설치하고 Kafka·토픽·Deployment 가 준비될 때까지 기다린다
set -euo pipefail

KIND_CLUSTER=${KIND_CLUSTER:-commerce-saga}
NAMESPACE=${NAMESPACE:-commerce}
RELEASE=${RELEASE:-commerce}
TAG=${TAG:-dev}
STRIMZI_VERSION=${STRIMZI_VERSION:-1.3.1}
SKIP_BUILD=${SKIP_BUILD:-false}
SERVICES="auth-service order-service inventory-service point-service payment-service notification-service gateway"

cd "$(dirname "$0")/.."

if [ "$SKIP_BUILD" != true ]; then
  echo "== 이미지 빌드"
  ./gradlew -q $(for s in $SERVICES; do printf ':services:%s:bootJar ' "$s"; done)
  for s in $SERVICES; do
    docker build -q -f deploy/docker/service.Dockerfile \
      --build-arg JAR="services/$s/build/libs/$s-0.0.1-SNAPSHOT.jar" -t "commerce-saga/$s:$TAG" . > /dev/null
  done
  docker build -q -t "commerce-saga/frontend:$TAG" frontend > /dev/null
fi

echo "== kind 노드에 이미지 넣기"
for s in $SERVICES frontend; do
  kind load docker-image "commerce-saga/$s:$TAG" --name "$KIND_CLUSTER" > /dev/null
done

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
for d in redis $SERVICES frontend; do
  kubectl -n "$NAMESPACE" rollout status deployment/"$d" --timeout=8m
done
kubectl -n "$NAMESPACE" get pods
echo "OK: 배포 완료. scripts/k8s-port-forward.sh 로 로컬 포트에 연결한다"
