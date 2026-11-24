# k8s 의 Kafka Connect (Strimzi KafkaConnect, 후속 3 K-2). Strimzi Kafka 이미지에 Debezium Postgres 플러그인을 얹는다.
# compose 의 infra/connect/Dockerfile 과 같은 플러그인·체크섬. Strimzi 는 /opt/kafka/plugins 아래 플러그인을 읽는다.
# Strimzi 의 KafkaConnect build(이미지를 클러스터에서 빌드해 레지스트리에 올림)는 kind 에 레지스트리가 없어 쓰지 않는다.
#
#   docker build -f deploy/docker/connect.Dockerfile -t commerce-saga/connect:dev deploy/docker
#
# Strimzi·Kafka 버전을 바꾸면 차트 values 의 kafka.version 과 같이 바꾼다
FROM quay.io/strimzi/kafka:1.3.1-kafka-4.3.2

ARG DEBEZIUM_VERSION=3.7.1.Final
ARG DEBEZIUM_SHA512=d118c8eb994153c9cb687ee9071d09d07b5a03aafc03f5d898bd8ffa86ba801342be30723736f8d2803ae4a708a33295335f54d9f1af99d8c348461579a85868

USER root
# Maven Central 이 CI 러너에 429(요청 제한)를 오래 줄 때가 있어, 같은 내용의 Google 미러와 번갈아 간격을 늘리며 받는다.
# 어느 쪽에서 받든 아래 SHA-512 로 같은 파일인지 확인한다
RUN set -eux; \
    mkdir -p /opt/kafka/plugins; \
    path="io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz"; \
    for i in 1 2 3 4 5; do \
      curl -fsSL -o /tmp/debezium.tar.gz "https://repo1.maven.org/maven2/$path" && break; \
      curl -fsSL -o /tmp/debezium.tar.gz "https://maven-central.storage-download.googleapis.com/maven2/$path" && break; \
      [ "$i" = 5 ] && exit 1; \
      sleep $((i * 15)); \
    done; \
    echo "${DEBEZIUM_SHA512}  /tmp/debezium.tar.gz" | sha512sum -c -; \
    tar -xzf /tmp/debezium.tar.gz -C /opt/kafka/plugins; \
    rm /tmp/debezium.tar.gz
USER 1001
