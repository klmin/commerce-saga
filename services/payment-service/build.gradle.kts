plugins {
    id("commerce.service-conventions")
}

dependencies {
    implementation(project(":libs:messaging-contract"))
    implementation(project(":libs:outbox"))

    // 애그리거트 id 는 UUIDv7 (시간순이라 PK 인덱스 삽입이 끝에 몰림, JDK 25 에는 v7 생성기가 없음)
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
