plugins {
    id("commerce.service-conventions")
}

// 회원·로그인·토큰 발급 (ADR 0008). 가입을 auth.events(UserRegistered) 로 알린다 (outbox, 후속 2 A-3-2)
dependencies {
    implementation(project(":libs:messaging-contract"))
    implementation(project(":libs:outbox"))
    implementation("com.github.f4b6a3:uuid-creator:6.1.1") // 사용자 id UUIDv7 (ADR 0018)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // OIDC 인가 서버(후속 5, ADR 0017). 등록된 클라이언트·인가 기록은 auth_db(JDBC), SSO 로그인 세션은 Redis
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.testcontainers:testcontainers-postgresql")
}
