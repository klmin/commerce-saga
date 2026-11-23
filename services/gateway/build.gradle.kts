plugins {
    id("commerce.boot-app-conventions")
}

// API Gateway (ADR 0008). 리액티브(WebFlux) Spring Cloud Gateway 라 서블릿 서비스 공통 설정(service-conventions)을 쓰지 않는다
dependencyManagement {
    imports {
        // Spring Cloud 2026.0 = Gateway 5.1, Spring Boot 4.2 기준
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2026.0.0")
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    // 사용자별 rate limit (RedisRateLimiter)
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
}
