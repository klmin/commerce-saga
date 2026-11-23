plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// convention plugin 안에서 id("org.springframework.boot") 등을 버전 없이 쓰기 위해 여기서 버전을 정한다
dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.2.0")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")
}
