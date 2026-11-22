# AGENTS.md

AI 코딩 도구(Claude, Codex 등)가 이 저장소에서 작업·리뷰할 때 참고하는 지침.

## 프로젝트 맥락

커머스 주문(주문·재고·포인트·결제)을 MSA로 나누고, 서비스 간 정합성을 **Saga + Transactional Outbox + Kafka (+ Debezium)** 로 맞추는 포트폴리오 프로젝트. 배경과 설계 결정은 [docs/commerce-msa-portfolio-brief.md](docs/commerce-msa-portfolio-brief.md), 결정 기록은 `docs/adr/`.

- Java 25, Spring Boot 4.1, Gradle Kotlin DSL 멀티모듈 모노레포
- PostgreSQL(서비스별 DB), Flyway, Kafka(KRaft), Spring for Apache Kafka, Debezium(Kafka Connect, 3단계)
- 테스트: JUnit 5, ArchUnit, Testcontainers(Postgres, Kafka)

## 구성

```
services/<name>-service/   독립 실행·배포되는 Spring Boot 앱. 서비스마다 자기 DB만 사용
libs/messaging-contract/   커맨드·이벤트 메시지 스키마(record)와 토픽 이름만
libs/outbox/               outbox 저장 + relay (서비스는 adapter.out 에서만 사용)
build-logic/               공통 빌드 설정 (convention plugin)
infra/                     docker-compose (Postgres, Kafka, Kafka UI, Kafka Connect)
scripts/                   커넥터 등록, 부하 생성, 장애 주입, 스모크 테스트
docs/adr/                  설계 결정 기록
docs/scenarios/            시나리오별 재현 방법·결과
```

| 서비스 | 역할 |
|---|---|
| order-service | 주문 애그리거트 + 주문 Saga 오케스트레이터 (Saga 상태를 DB에 저장) |
| inventory-service | 재고 예약 / 해제 |
| point-service | 포인트 차감 / 환불 |
| payment-service | 가짜 PG (지연·실패·타임아웃 주입) |
| notification-service | `order.events` 순수 소비자 (코레오그래피) |

## 명령

```bash
./gradlew build                              # 전체 (JDK 25는 툴체인이 자동으로 받음)
./gradlew :services:order-service:build      # 서비스 하나
docker compose -f infra/docker-compose.yml up -d
```

작업을 끝내기 전에 바뀐 모듈의 빌드를 돌려 통과시킨다. Docker가 없으면 Testcontainers 테스트는 skip되고 CI에서만 돈다.

## 서비스 경계 규칙 (MSA)

- **다른 서비스의 DB·테이블에 접근하지 않는다.** 로컬은 Postgres 하나에 DB를 나눠 쓰지만, 서비스마다 DB와 계정이 따로다. 다른 서비스 데이터가 필요하면 메시지로 받는다
- **서비스끼리 모듈을 공유하지 않는다.** `services/*` 간 의존 금지. 공유는 `libs/messaging-contract`(스키마)와 `libs/outbox`(인프라)까지. 도메인 클래스를 libs로 옮기지 않는다
- 서비스 간 통신은 Kafka 메시지. 서비스 간 동기 HTTP 호출을 새로 만들지 않는다
- 메시지 종류와 토픽
  - 커맨드(명령형, 수신자 하나, 응답 기대): `<수신 서비스>.command` — 수신 서비스 소유
  - 응답: `<응답 서비스>.reply` — 오케스트레이터만 구독
  - 이벤트(과거형 사실, 수신자 0~N): `<발행 서비스>.events` — 발행 서비스 소유
- Kafka 메시지 키는 `orderId` (주문 단위 순서 보장)
- 메시지에 JWT·비밀값을 싣지 않는다. 식별자(`userId`, `orderId`)만

## Saga / Outbox / 멱등성

- 메인 주문 Saga는 order-service가 오케스트레이션한다. 후속 처리(알림·정산·통계)는 `order.events` 코레오그래피
- 보상 순서는 정방향의 역순: 결제 실패 → `RefundPoint` → `ReleaseStock` → 주문 취소
- **모든 메시지 발행은 outbox를 거친다.** `kafkaTemplate.send()`를 비즈니스 코드에서 직접 부르지 않는다
  - 오케스트레이터: Saga 상태 변경 + 커맨드 outbox 저장 = 한 트랜잭션
  - 참여 서비스: 로컬 변경 + 응답 outbox 저장 = 한 트랜잭션
  - outbox 스키마는 Debezium Outbox Event Router 호환(`aggregatetype` = 토픽, `aggregateid` = 키, `type`, `payload`). "넣고 지운다"
- 전달은 at-least-once. **컨슈머는 멱등이어야 한다** — `processed_message(message_id)`를 비즈니스 처리와 같은 트랜잭션에 INSERT, 중복이면 skip
- 처리 불가 메시지는 retry 토픽 → DLQ. 예외를 삼키고 오프셋만 커밋하지 않는다
  - 일시 오류(DB 장애 등)는 복구될 때까지 재시도, 재시도해도 같은 오류(본문·헤더 이상, 데이터 불일치)는 `<토픽>.DLT`로 원본 보존 (각 서비스 `KafkaErrorHandlingConfig`)
  - 커맨드·응답 토픽은 계약에 없는 타입이 오면 DLT로 보낸다. 이벤트 토픽(`*.events`)은 소비자가 여럿이라 관심 없는 타입은 건너뛴다. 단 `type` 헤더 자체가 없거나 비면 어느 토픽이든 DLT
- 보상 커맨드(`ReleaseStock`, `RefundPoint`)는 여러 번 와도, 앞 단계가 없었어도 안전하고 항상 같은 응답을 보낸다. Saga는 현재 단계에서 기다리는 응답이 아니면 무시한다 (ADR 0003)

## 서비스 내부 구조 (헥사고날 + DDD)

패키지 루트는 `com.klmin.commerce.<서비스명에서 -를 뺀 것>` (예: `com.klmin.commerce.orderservice`), 그 아래 기능(feature) 패키지(예: `order`). notification-service처럼 로직이 거의 없는 서비스는 단순 구조 허용.

```
<feature>/domain/                    애그리거트, 도메인 규칙. Spring/JPA/Kafka/Validation 의존 금지
<feature>/application/port/in/       유스케이스 인터페이스 (+ Command/Query record)
<feature>/application/port/out/      외부 의존 포트 (저장, 메시지 발행)
<feature>/application/service/       유스케이스 구현. @Transactional은 여기
<feature>/adapter/in/web/            컨트롤러, Request/Response DTO
<feature>/adapter/in/messaging/      @KafkaListener -> port.in 호출
<feature>/adapter/out/persistence/   JPA 엔티티/리포지토리, 포트 구현 (package-private)
<feature>/adapter/out/messaging/     outbox 저장 포트 구현
common/error, common/web, common/config
```

- `HexagonalArchitectureTest`(ArchUnit)가 의존 방향을 검사한다: `adapter -> application -> domain`, 컨트롤러·리스너는 `port.in`만, adapter.in과 adapter.out은 서로 참조 금지
- 애그리거트 = 일관성 경계. 리포지토리는 애그리거트 루트마다 하나. 다른 애그리거트는 ID로만 참조
- 쓰기(명령)만 애그리거트를 거친다. 조회·목록·통계는 DTO 직접 조회 허용
- 영속성은 도메인 모델과 JPA 엔티티를 분리하고 어댑터에서 매핑한다 (ADR 0002). 엔티티를 어댑터 밖으로 내보내지 않는다
- 도메인 규칙 위반은 `DomainValidationException`(400), 없는 리소스는 `NotFoundException` 상속(404). 오류 응답은 Problem Details
- 스키마 변경은 Flyway `V{n}__설명.sql`로만. 머지된 마이그레이션은 고치지 않는다
- 현재 시각은 `Clock` Bean 주입 (`Instant.now()` 직접 호출 X)
- 사용자 식별: 1~3단계는 `X-User-Id` 헤더, 4단계부터 JWT. **리소스 조회·변경 시 소유권을 서비스 내부에서 검사한다**

### 테스트

- domain: 순수 단위 테스트
- application.service: `port.out`을 인메모리 구현으로 바꿔 단위 테스트
- adapter.in.web: `@WebMvcTest` + `@MockitoBean`
- adapter.out / messaging: Testcontainers(Postgres, Kafka) + `@Testcontainers(disabledWithoutDocker = true)`
- Saga 흐름·보상·중복 전달은 시나리오 테스트로 증명하고 `docs/scenarios/`에 결과를 남긴다

## Git / PR

- `main`은 보호됨. 브랜치 → PR로만 머지
- 브랜치: `feat/`, `fix/`, `refactor/`, `chore/`, `docs/`, `test/`
- 커밋: Conventional Commits, 설명은 한국어. scope는 서비스·모듈 이름. 예) `feat(order): 주문 생성 API 추가`
- PR 하나에 목적 하나. 문서·리뷰는 한국어
- 설정 파일·테스트 코드에 실제 키·비밀번호를 넣지 않는다 (로컬 개발용 값만)

## Review guidelines

- 리뷰 코멘트와 요약은 반드시 한국어로 작성
- 우선순위 (높은 순)
  1. 분산 정합성: Saga 보상 누락·순서 오류, outbox 원자성 깨짐, 멱등성 누락 (아래 체크리스트)
  2. 보안: 소유권 검사 누락(IDOR), 서비스 경계 위반
  3. 버그, 예외 처리 누락, null 처리
  4. 트랜잭션 경계, 동시성(재고·포인트 동시 차감, 락, 재시도)
  5. DB: Flyway 마이그레이션 호환성, 인덱스, N+1
  6. 테스트 누락 (특히 실패·보상·중복 경로)
- 생략할 것: 네이밍, 포맷팅, 취향 수준의 리팩터링 제안, 이미 동작하는 코드에 대한 대안 제시

### 분산 정합성 체크리스트

변경된 코드가 아래 항목에 닿으면 반드시 확인한다.

- Saga 보상
  - 새 단계를 추가했다면 실패 시 이미 성공한 앞 단계를 모두 역순으로 보상하는지
  - 보상 커맨드 자체가 실패·중복될 때도 안전한지 (이미 해제된 재고를 또 해제, 환불 두 번 등)
  - Saga 상태 전이가 허용되지 않은 순서(예: `CANCELLED` 이후 `PaymentApproved` 도착)를 무시·처리하는지. 늦게 도착한 응답으로 상태가 되돌아가지 않는지
  - 결제 타임아웃처럼 결과가 애매한 경우 바로 보상하지 않고 조회 후 보정하는지
- Outbox
  - DB 변경과 outbox INSERT가 **같은 트랜잭션**인지. `@Transactional` 밖에서 저장하거나, 커밋 후 별도로 발행하거나, `kafkaTemplate.send()`를 직접 부르면 이중 쓰기 (P0)
  - outbox 행의 `aggregatetype`(토픽), `aggregateid`(키 = orderId)가 규칙에 맞는지
  - relay가 send ack를 기다린 뒤 삭제하는지, 다중 인스턴스에서 `FOR UPDATE SKIP LOCKED` 등으로 같은 행을 중복 처리하지 않는지
- 멱등 컨슈머
  - 상태를 바꾸는 컨슈머가 `messageId` 중복 검사를 비즈니스 처리와 같은 트랜잭션에서 하는지. 재전송 시 재고·포인트가 두 번 반영되면 P0
  - 리스너에서 예외를 삼켜 메시지가 유실되지 않는지, 재시도할 수 없는 오류가 무한 재시도되지 않는지(DLQ)
- 서비스별 DB 분리
  - 다른 서비스의 DB·스키마·테이블을 조회하거나 datasource를 공유하는 코드
  - `services/*` 사이 의존, 도메인 클래스를 `libs/`로 옮겨 공유하는 변경
  - 서비스 간 동기 HTTP 호출 추가
- 소유권·권한
  - 주문·포인트 등 사용자 리소스를 ID로 조회·취소할 때 현재 사용자(`X-User-Id` / JWT subject)와 소유자를 비교하는지. 빠뜨리면 IDOR (P0)
  - 메시지에 토큰이나 비밀값이 실리지 않는지
- 동시성
  - 재고·포인트 차감이 동시 요청에서 음수가 되지 않는지 (조건부 UPDATE, 낙관적 락 등)
- 민감정보
  - 로그·예외 메시지·API 응답에 비밀값이 남는지, `application*.yml`·테스트 코드에 실제 키·비밀번호가 들어가는지

### 심각도 기준

- P0: 데이터 불일치·유실(outbox 원자성 깨짐, 보상 누락, 중복 반영), 다른 사용자 리소스 접근(IDOR), 서비스 DB 경계 위반
- P1: 늦은·중복 메시지에 의한 잘못된 상태 전이, 실패 시 정리 누락, 예외 처리 누락으로 인한 장애
- P2: 성능 저하, N+1, 테스트 누락
- P3(사소한 문제)는 코멘트하지 않음
