// services/* 공통: 서블릿(Spring MVC) 기반 서비스
plugins {
    id("commerce.boot-app-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
