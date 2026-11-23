plugins {
    id("commerce.service-conventions")
}

// 회원·로그인·토큰 발급 (ADR 0008). 다른 서비스와 메시지를 주고받지 않아 Kafka·outbox 를 쓰지 않는다
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // JWT 서명(JwtEncoder)·검증(로그인 기기 관리 API), 비밀번호 해시(BCrypt)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}
