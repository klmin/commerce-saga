plugins {
    id("commerce.service-conventions")
}

// 회원·로그인·토큰 발급 (ADR 0008). 가입을 auth.events(UserRegistered) 로 알린다 (outbox, 후속 2 A-3-2)
dependencies {
    implementation(project(":libs:messaging-contract"))
    implementation(project(":libs:outbox"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.testcontainers:testcontainers-postgresql")
}
