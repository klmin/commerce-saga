plugins {
    id("commerce.service-conventions")
}

// 상품·가격의 주인 (후속 4-1, ADR 0016). 메시지를 주고받지 않아 Kafka·outbox 가 없다.
// 다른 서비스(order)는 공개 조회 API 를 동기로 부른다
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.f4b6a3:uuid-creator:6.1.1") // 상품 id UUIDv7 (ADR 0018)
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.testcontainers:testcontainers-postgresql")
}
