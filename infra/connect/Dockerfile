# Kafka Connect + Debezium Postgres 커넥터.
# Connect 런타임을 브로커와 같은 apache/kafka 이미지(같은 버전)로 띄우고, Debezium 플러그인만 Maven Central 에서 받아 얹는다.
# 브로커 버전을 올릴 때 이 FROM 도 같이 올린다 (docker-compose.yml 의 kafka 이미지, 서비스 TestcontainersConfiguration)
FROM apache/kafka:4.4.0

ARG DEBEZIUM_VERSION=3.7.1.Final
# Maven Central 의 debezium-connector-postgres-<버전>-plugin.tar.gz.sha512. 버전을 바꾸면 같이 바꾼다
ARG DEBEZIUM_SHA512=d118c8eb994153c9cb687ee9071d09d07b5a03aafc03f5d898bd8ffa86ba801342be30723736f8d2803ae4a708a33295335f54d9f1af99d8c348461579a85868

USER root
# Maven Central 이 CI 러너에 429(요청 제한)를 오래 줄 때가 있어, 같은 내용의 Google 미러와 번갈아 간격을 늘리며 받는다.
# 어느 쪽에서 받든 아래 SHA-512 로 같은 파일인지 확인한다
RUN set -eux; \
    mkdir -p /opt/kafka/plugins; \
    path="io/debezium/debezium-connector-postgres/${DEBEZIUM_VERSION}/debezium-connector-postgres-${DEBEZIUM_VERSION}-plugin.tar.gz"; \
    for i in 1 2 3 4 5; do \
      wget -q -O /tmp/debezium.tar.gz "https://repo1.maven.org/maven2/$path" && break; \
      wget -q -O /tmp/debezium.tar.gz "https://maven-central.storage-download.googleapis.com/maven2/$path" && break; \
      [ "$i" = 5 ] && exit 1; \
      sleep $((i * 15)); \
    done; \
    echo "${DEBEZIUM_SHA512}  /tmp/debezium.tar.gz" | sha512sum -c -; \
    tar -xzf /tmp/debezium.tar.gz -C /opt/kafka/plugins; \
    rm /tmp/debezium.tar.gz; \
    chown -R appuser:appuser /opt/kafka/plugins

COPY --chown=appuser:appuser connect-distributed.properties /opt/kafka/config-connect/connect-distributed.properties

USER appuser
ENTRYPOINT ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config-connect/connect-distributed.properties"]
