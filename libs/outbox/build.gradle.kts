plugins {
    id("commerce.library-conventions")
}

// Transactional Outbox: 비즈니스 트랜잭션 안에서 outbox 행을 저장하고, relay 가 Kafka 로 발행한다.
// 서비스는 adapter.out 에서만 쓴다 (relay 를 Debezium 으로 바꿔도 도메인·애플리케이션은 그대로)
dependencies {
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    // 메시지 id(outbox.id) 는 UUIDv7
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
