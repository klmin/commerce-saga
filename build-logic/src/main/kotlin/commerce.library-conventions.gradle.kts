// libs/* 공통: 서비스가 가져다 쓰는 일반 jar. 의존성 버전은 Spring Boot BOM 을 따른다
plugins {
    id("commerce.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
