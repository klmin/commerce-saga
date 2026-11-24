plugins {
    id("commerce.service-conventions")
}

dependencies {
    implementation(project(":libs:messaging-contract"))
    implementation(project(":libs:outbox"))
    // 지급 기록 id 는 UUIDv7 (주문 id 와 같은 시간순, 포인트 내역을 id 하나로 커서 페이징)
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
}
