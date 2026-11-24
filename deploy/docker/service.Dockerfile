# 백엔드 서비스 공용 이미지 (후속 3 K-1). Gradle 이 만든 bootJar 를 받아 실행만 한다.
#
#   ./gradlew :services:order-service:bootJar
#   docker build -f deploy/docker/service.Dockerfile \
#     --build-arg JAR=services/order-service/build/libs/order-service-0.0.1-SNAPSHOT.jar \
#     -t commerce-saga/order-service:dev .
#
# 빌드는 이미지 밖(CI·로컬 Gradle 캐시)에서 한다. 이미지 안에서 Gradle 을 돌리면 서비스 7개마다 의존성을 다시 받는다.
# Spring Boot 레이어(의존성·애플리케이션)로 풀어 두어 코드만 바뀌면 마지막 레이어만 바뀐다.

FROM eclipse-temurin:25-jre AS extract
ARG JAR
WORKDIR /work
COPY ${JAR} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home-dir /app app
WORKDIR /app
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./
USER 10001
# 컨테이너 메모리 한도에 맞춰 힙을 잡는다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
