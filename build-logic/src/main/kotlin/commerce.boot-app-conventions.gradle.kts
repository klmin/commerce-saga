// 독립 실행되는 Spring Boot 앱 공통 (services/*, gateway): 관측성, 테스트
plugins {
    id("commerce.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // /actuator/prometheus (Prometheus 가 긁어 간다, ADR 0013)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // access token(JWT) 검증 (ADR 0008). Gateway 1차, 서비스마다 다시 검증
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Docker 이미지에는 실행 jar(bootJar) 하나만 쓴다
tasks.jar {
    enabled = false
}
