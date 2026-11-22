plugins {
    id("commerce.service-conventions")
}

// order.events 를 구독하는 순수 소비자 (코레오그래피). 로직이 거의 없어 헥사고날 대신 단순 구조로 둔다.
// 발행하는 메시지가 없어 outbox 도 쓰지 않는다
dependencies {
    implementation(project(":libs:messaging-contract"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
}
