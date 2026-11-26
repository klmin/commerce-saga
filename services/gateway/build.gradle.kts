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
    // BFF (후속 5-2, ADR 0017): SAS 로 로그인(oauth2Login)해 토큰을 세션에 두고 TokenRelay 로 서비스에 Bearer 를 붙인다.
    // 세션은 Redis(Spring Session, 쿠키 GW_SESSION) 라 인스턴스가 여럿이어도 같다
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
}
