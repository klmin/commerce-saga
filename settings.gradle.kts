pluginManagement {
    // 서비스 공통 빌드 설정(convention plugin)
    includeBuild("build-logic")
}

plugins {
    // 툴체인(JDK 25)이 PC에 없으면 Gradle이 자동으로 내려받는다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "commerce-saga"

// services/*, libs/*, tools/* 아래에 build.gradle.kts 가 있는 디렉터리를 모두 모듈로 등록한다
// 예) services/order-service -> :services:order-service
listOf("services", "libs", "tools").forEach { group ->
    rootDir.resolve(group).listFiles()
        ?.filter { it.isDirectory && it.resolve("build.gradle.kts").exists() }
        ?.sortedBy { it.name }
        ?.forEach { include(":$group:${it.name}") }
}
