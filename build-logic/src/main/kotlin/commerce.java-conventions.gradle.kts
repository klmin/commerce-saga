// 모든 모듈 공통: Java 25 툴체인, 테스트 설정
plugins {
    java
}

group = "com.klmin.commerce"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        // Docker가 없어 skip된 테스트를 CI 로그에서 바로 보이게
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println(
                    "${project.path} Tests: ${result.testCount}, passed: ${result.successfulTestCount}, " +
                        "failed: ${result.failedTestCount}, skipped: ${result.skippedTestCount}"
                )
            }
        }
    })
}
