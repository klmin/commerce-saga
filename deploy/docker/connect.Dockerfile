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
RUN set -eux; \
    mkdir -p /opt/kafka/plugins; \
    url="https://repo1.maven.org/maven2/io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz"; \
    curl -fsSL -o /tmp/debezium.tar.gz "$url"; \
    echo "${DEBEZIUM_SHA512}  /tmp/debezium.tar.gz" | sha512sum -c -; \
    tar -xzf /tmp/debezium.tar.gz -C /opt/kafka/plugins; \
    rm /tmp/debezium.tar.gz
USER 1001
