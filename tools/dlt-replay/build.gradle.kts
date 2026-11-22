plugins {
    id("commerce.library-conventions")
    application
}

// 운영 도구: DLT 조회·재처리 (docs/scenarios/06-poison-message-dlt.md, ADR 0012).
// 서비스가 아니라 사람이 장애 복구 때 실행하는 CLI 라 Spring 없이 kafka-clients 만 쓴다.
// 실행: scripts/dlt.sh list inventory.command.DLT
dependencies {
    implementation(project(":libs:messaging-contract"))
    implementation("org.apache.kafka:kafka-clients")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
}

application {
    mainClass = "com.klmin.commerce.tools.dlt.DltTool"
    applicationName = "dlt"
}
