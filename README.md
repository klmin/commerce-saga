# commerce-saga

커머스 주문(주문·재고·포인트·결제)을 마이크로서비스로 나누고, 서비스 간 정합성을 **Saga(오케스트레이션) + Transactional Outbox + Kafka + Debezium(CDC)** 으로 맞추는 프로젝트.

> 작업 중. 진행 계획과 설계 근거는 [docs/commerce-msa-portfolio-brief.md](docs/commerce-msa-portfolio-brief.md) 참고.

## 기술 스택

Java 25, Spring Boot 4.1, Gradle Kotlin DSL(멀티모듈), PostgreSQL, Flyway, Kafka(KRaft), Spring for Apache Kafka, Debezium, Testcontainers

## 구성

```
services/   주문·재고·포인트·결제·알림 서비스 (서비스별 DB, 독립 실행)
libs/       메시지 계약(스키마), outbox
build-logic/ 공통 빌드 설정
infra/      로컬 인프라 (docker-compose)
docs/       ADR, 시나리오
```

## 빌드

```bash
./gradlew build
```
