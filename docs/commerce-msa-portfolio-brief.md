# 커머스 MSA 포트폴리오 프로젝트 브리프

> 새 컨텍스트(새 세션)에서 이 문서 하나만 읽고 작업을 시작할 수 있게 정리한 문서.
> 작성일: 2026-11-22, 진행 단계 순서 수정: 2026-11-23 (5절)

---

## 1. 목적

- **MSA, 분산 트랜잭션(Saga), 이벤트 기반 아키텍처(EDA)** 로 커머스 주문을 구현하고, 서비스 간 정합성을 **Transactional Outbox + Kafka + Debezium(CDC)** 으로 맞춘다.
- 주제는 **분산 시스템의 정합성**: 실패·중복·지연·장애 속에서도 주문·재고·포인트·결제가 어긋나지 않음을 코드·테스트·시나리오로 증명한다.

### 도메인: 커머스 주문 (주문·재고·포인트·결제)

- 누구나 아는 도메인이라 업무 설명 없이 기술적 문제에 집중할 수 있다.
- 돈·재고가 걸린 쓰기 트랜잭션이 여러 서비스에 걸치고, 실패 시 보상이 필요하다 → Saga가 자연스럽다.
- 검토 후 제외한 대안: 주식 시세·차트 사이트. 수집은 쓰기 폭주, 서비스는 읽기 폭주라 Kafka·스트리밍·실시간에는 좋지만, 쓰기가 append-only이고 서비스 간 정합성이 필요 없어 Saga·Outbox가 들어갈 자리가 없다. 하려면 "시세 + 모의투자"로 별도 프로젝트.

---

## 2. 핵심 개념 정리 (설계 판단의 근거)

### MSA의 실제 핵심
- 얻는 것: **독립 배포, 독립 확장, 장애 격리, 팀 자율성(바운디드 컨텍스트 단위 소유)**
- 대가: 분산 시스템 복잡도. **서비스 간 원자성(분산 ACID)을 포기**하고 최종 일관성을 받아들인다.
- 오해 주의: MSA는 지연을 줄이지 않는다(네트워크 홉 증가). 비동기·논블로킹은 모놀리스에도 해당하는 이야기다.

### MSA와 EDA / Kafka / Debezium의 관계
EDA·Kafka·Debezium이 MSA를 위해 생긴 건 아니지만, MSA가 만든 문제를 풀기에 맞아서 함께 쓰인다.

| MSA가 만든 문제 | 해결 수단 |
|---|---|
| 동기 호출 체인 → 결합, 연쇄 장애, 지연 누적 | 비동기 이벤트 (EDA, Kafka) |
| 서비스별 DB → 다른 서비스 데이터가 필요 | 이벤트로 데이터 전파 |
| DB 저장 + 메시지 발행 이중 쓰기 → 불일치 | Transactional Outbox + CDC (Debezium) |
| 분산 트랜잭션 불가 | Saga |

한 문장 요약:
> MSA는 독립 배포·확장·장애 격리를 얻는 대신 서비스 간 원자성을 포기하는 구조이고, 그로 인해 생기는 결합·데이터 분산·이중 쓰기 문제를 EDA와 Kafka, Outbox + CDC로 풀었다.

### 층위 구분

| 층위 | 무엇 |
|---|---|
| 서비스 내부 구조 | 헥사고날 + DDD 애그리거트 |
| 서비스 사이 구조 | MSA |
| 서비스 간 통신 방식 | EDA (비동기 메시지) |
| 메시지 운반 | Kafka |
| 메시지 안전한 발행 | Outbox + Debezium |

### DDD / 애그리거트
- DDD는 모델링 방법론이고 JPA는 저장 기술. **애그리거트를 JPA와 엮어야 DDD인 것은 아니다.**
- 애그리거트 = 일관성 경계. 경계 안(예: Order + OrderLine)만 한 트랜잭션으로 통째 저장·로딩. 경계 기준은 "즉시 같이 맞아야 하는 불변식이 있는가". 작게 잡는다.
- 다른 애그리거트는 **ID로만 참조**.
- **리포지토리는 애그리거트 루트마다 하나.** 내부 엔티티는 리포지토리가 없다.
- **쓰기(명령)만 애그리거트를 거친다.** 조회·목록·통계는 jOOQ 등으로 바로 DTO 조회(CQRS-lite).
- "한 트랜잭션 = 애그리거트 하나"는 지향점. 서비스 내부에서 타협하면 이유를 ADR로 남긴다.
- 헥사고날 영속성 선택지 (택1 후 ADR로 이유 기록):
  - A. 도메인 모델과 JPA 엔티티 분리 + 매퍼 (정석, 보일러플레이트 증가)
  - B. 도메인 모델에 JPA 어노테이션 직접 (실용적 타협)
  - C. Spring Data JDBC / jOOQ 어댑터 (애그리거트 단위 저장 철학과 잘 맞음)
- 로직이 거의 없는 서비스(알림 등)까지 헥사고날로 무겁게 만들 필요는 없다.

### Saga: 오케스트레이션 vs 코레오그래피
- Saga는 패턴이고 구현 방식이 두 가지: **오케스트레이션 Saga / 코레오그래피 Saga**.
- Kafka는 그냥 파이프. 방향은 **메시지 종류**로 만든다.

| | 커맨드 (오케스트레이션) | 이벤트 (코레오그래피) |
|---|---|---|
| 의미 | "재고 예약해라" (명령형) | "주문이 생성됐다" (과거형 사실) |
| 수신자 | 딱 하나 | 0~N, 발행자는 모름 |
| 응답 | 기대함 (reply) | 기대 안 함 |
| 토픽 | `inventory.command`: 수신 서비스 소유, 그 서비스만 구독 | `order.events`: 발행 서비스 소유, 누구나 구독 |
| 흐름 결정 | 오케스트레이터가 앎 | 각 서비스가 스스로 판단 |

- **결정: 메인 주문 Saga = 오케스트레이션, 후속 처리 = 코레오그래피.**
  - 메인: 단계 4개, 보상 순서가 중요 → 상태 머신 한 곳에서 추적·타임아웃·재시도.
  - 후속(알림·정산·통계): 주문 성공에 영향 없음, 소비자를 추가해도 order-service 수정 불필요.
- 여유가 생기면 같은 흐름을 코레오그래피 Saga로 구현한 브랜치를 두고 ADR로 비교.

### Outbox relay: 스케줄러 vs Debezium
- 두 방식 모두 앱은 **비즈니스 데이터 + outbox 행을 한 트랜잭션에 저장**. 차이는 outbox → Kafka 구간.
  - 스케줄러: `@Scheduled`에서 outbox 조회 → `kafkaTemplate.send()` → 행 삭제.
  - Debezium: Kafka Connect 위 Debezium 커넥터가 Postgres WAL을 읽어 Kafka로 발행. 커넥터 JSON 설정으로 매핑, 발행 코드 없음.
- Kafka는 두 방식 모두에 들어간다. Debezium은 Kafka에 넣어 주는 생산자일 뿐. 소비 쪽은 둘 다 일반 `@KafkaListener`.

| | 스케줄러 | Debezium |
|---|---|---|
| 인프라 | 추가 없음 | Kafka Connect 필요 |
| 지연 | 폴링 주기 (Saga 단계마다 누적) | 수십 ms |
| DB 부하 | 주기적 SELECT/DELETE | 거의 없음 (WAL 읽기) |
| 다중 인스턴스 | `FOR UPDATE SKIP LOCKED` 또는 ShedLock | 커넥터가 WAL 순서대로 |
| 순서 | `created_at`은 트랜잭션 시작 시각이라 커밋 순서와 다를 수 있음 | 커밋 순서 그대로 |
| 운영 리스크 | 단순 | 커넥터 정지 시 replication slot 때문에 WAL 적체 → 디스크 풀 |

- **결정: 스케줄러로 먼저 구현 → Debezium으로 교체 → 측정 비교 → ADR.** 헥사고날에서 relay는 어댑터라 도메인 수정 없이 교체 가능 (헥사고날 효과의 근거).
- 시간이 부족하면 처음부터 Debezium만 구현하고 비교는 ADR에 글로만.
- 판단 기준: "무조건 Debezium"이 아니라 "규모가 작으면 스케줄러가 합리적. 이 프로젝트는 Saga 단계별 지연·서비스 수 때문에 Debezium."
- Debezium을 쓰는 기준: 유실되면 안 되는 메시지(Saga 커맨드·응답·도메인 이벤트 전부). 클릭 로그·지표처럼 유실 허용이면 `KafkaTemplate` 직접 발행도 가능.

---

## 3. 아키텍처

### 서비스 (5개)

| 서비스 | 역할 | 내부 구조 |
|---|---|---|
| order-service | 주문 애그리거트 + **Saga 오케스트레이터** (Saga 상태를 DB에 저장) | 헥사고날 + DDD |
| inventory-service | 재고 예약 / 해제 | 헥사고날 + DDD |
| point-service | 포인트 차감 / 환불 | 헥사고날 + DDD |
| payment-service | 가짜 PG (지연·실패·타임아웃 주입 가능) | 헥사고날 |
| notification-service | `OrderCompleted` 등 순수 이벤트 소비자 (코레오그래피) | 단순 구조 |

나중 단계 추가 서비스: `api-gateway`, `auth-service` (후속 2), `product-service`, `search-indexer` (후속 4). 아래 각 절 참고.

- 레포: https://github.com/klmin/commerce-saga (public), 로컬 `C:\intelljProject\commerce-saga`

### 주문 Saga 흐름

```
order-service (오케스트레이터)
  │ inventory.command: ReserveStock ──▶ inventory ──▶ inventory.reply: StockReserved
  │ point.command:     UsePoint     ──▶ point     ──▶ point.reply:     PointUsed
  │ payment.command:   Pay          ──▶ payment   ──▶ payment.reply:   PaymentApproved / PaymentFailed
  │   └ 실패 시 보상: point.command: RefundPoint → inventory.command: ReleaseStock → 주문 취소
  │
  └ 성공 시 order.events: OrderCompleted ──▶ notification, (settlement, stats) 코레오그래피
```

- 모든 메시지는 outbox를 거친다.
  - 오케스트레이터: Saga 상태 변경 + 커맨드 outbox 저장 = 한 트랜잭션.
  - 참여 서비스: 로컬 변경 + 응답 outbox 저장 = 한 트랜잭션.
- Kafka 메시지 키 = `orderId` (주문 단위 순서 보장).
- 결제 타임아웃의 애매한 상태(실제로는 승인됐을 수도 있음) → PG 조회 후 보정. 결과가 애매한 외부 호출의 대표 사례라 반드시 다룬다.

### Outbox 스키마 (Debezium Outbox Event Router 호환, 스케줄러도 그대로 사용)

```sql
create table outbox (
  id            uuid primary key,
  aggregatetype varchar     not null,  -- 토픽 라우팅 기준 (예: inventory.command)
  aggregateid   varchar     not null,  -- Kafka 메시지 키 (예: orderId)
  type          varchar     not null,  -- 메시지 타입 (예: ReserveStock)
  payload       jsonb       not null,
  created_at    timestamptz not null default now()
);
```

- **"넣고 지운다"로 두 방식 통일.** 발행 완료 컬럼 불필요.
- 스케줄러:
  ```sql
  select * from outbox order by created_at limit 100 for update skip locked;
  -- 각 행 kafkaTemplate.send(topic=aggregatetype, key=aggregateid, value=payload).get()  (ack 대기)
  -- delete from outbox where id in (...);  commit
  ```
  send 성공 후 delete 전에 죽으면 중복 발행 → 컨슈머 멱등 처리로 해결 (at-least-once).
- Debezium: INSERT 직후 삭제해도 WAL의 INSERT를 읽는다.

### Debezium 커넥터 예시

```json
{
  "name": "order-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.dbname": "order_db",
    "database.user": "debezium",
    "database.password": "<로컬 개발용 값>",
    "topic.prefix": "order",
    "table.include.list": "public.outbox",
    "plugin.name": "pgoutput",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "aggregatetype",
    "transforms.outbox.route.topic.replacement": "${routedByValue}"
  }
}
```

- Postgres `wal_level=logical` 필요.
- 커넥터는 Kafka Connect REST API로 등록 (`scripts/`에 등록 스크립트).

### 멱등 컨슈머
- 서비스마다 처리 이력 테이블(`processed_message(message_id pk, processed_at)`). 비즈니스 처리와 같은 트랜잭션에 INSERT, 중복이면 skip.
- 목표: 오프셋을 되감아 재전송해도 재고·포인트가 두 번 반영되지 않음.

### 실패 처리
- 일시 오류는 제자리 재시도, 영구 오류는 `<토픽>.DLT`로 보존 → 재처리 CLI(`scripts/dlt.sh`). 지연 retry 토픽은 같은 주문의 순서를 깨므로 쓰지 않는다 (ADR 0012, 처음 계획은 retry 토픽 → DLQ였다).

### 인증·인가 (후속 2)

추가 서비스: `api-gateway`(Spring Cloud Gateway), `auth-service`.

- **방식: 자체 auth-service가 RS256 JWT 발급 + `/.well-known/jwks.json`으로 공개키 노출.**
  - 각 서비스는 `spring-boot-starter-oauth2-resource-server` + `jwk-set-uri`로 검증. 검증 쪽은 SAS를 쓸 때와 동일 → 나중에 SAS로 바꿔도 리소스 서버는 그대로.
  - 자체 로그인 계정(이메일 + 비밀번호, BCrypt 등 적응형 해시).
  - Access token 짧게(약 15분), refresh token은 Redis 저장 + **회전(rotation) + 재사용 감지**. 로그아웃 시 폐기.
- **Spring Authorization Server(SAS)는 지금은 쓰지 않는다.** OAuth 2.1에서 password grant가 없어져 로그인이 브라우저 기반 authorization code + PKCE가 되므로 백엔드 중심 포폴에서 시연이 번거롭다. "외부 파트너 앱·SSO·OIDC 제공자가 필요할 때 SAS. 이 프로젝트는 퍼스트파티 클라이언트 하나라 자체 발급" → ADR로 기록.
- 검증 위치: **Gateway 1차 검증 + 각 서비스 Resource Server 재검증** (내부망 무조건 신뢰 안 함).
- **Kafka 메시지에 JWT를 싣지 않는다.** 커맨드·이벤트에는 `userId` 등 식별자만. 내부 토픽은 시스템 간 통신이고 토큰은 처리 시점에 만료돼 있을 수 있다.
- 1~3단계에서는 인증 없이 `X-User-Id` 헤더로 사용자를 대체하고, 후속 2에서 JWT로 교체. 자세한 설계는 PROGRESS "후속 2 설계 메모".

#### 역할

| 역할 | 권한 |
|---|---|
| `CUSTOMER` | 주문, 본인 주문·포인트 조회, 취소 |
| `ADMIN` | 상품·재고 관리, 전체 주문 조회, 강제 취소·환불, Saga 상태 조회 |
| `SELLER` | 마켓플레이스로 확장할 때만 추가. 본인 상품과 그 주문만 관리 |

- **역할보다 소유권 검사가 핵심.** `CUSTOMER`가 남의 주문 ID로 조회·취소하면 막혀야 한다(IDOR). 역할 검사는 Gateway·`@PreAuthorize`, 소유권 검사는 각 서비스 내부에서. 테스트로 증명.

### Redis (후속 2부터, 용도 한정)

| 용도 | 채택 |
|---|---|
| Refresh token 저장, 폐기 | O |
| **주문 API 멱등 키** (`Idempotency-Key` 헤더, 따닥 클릭 중복 주문 방지) | O (강력 추천) |
| Gateway rate limiting | O |
| 상품 상세 캐시 | 선택 |
| 재고 차감을 Redis로 | X. DB 기반 Saga와 기준 데이터가 둘로 갈림. 선착순 같은 별도 시나리오로만 |

### 상품 검색: Elasticsearch (후속 4)

추가 서비스: `product-service`, `search-indexer`.

```
product-service (Postgres) ──Debezium 테이블 CDC──▶ Kafka ──▶ search-indexer ──▶ Elasticsearch ◀── 검색 API
```

- CQRS: 쓰기는 Postgres, 검색은 ES.
- 여기서는 Debezium을 outbox가 아닌 **테이블 CDC(데이터 복제)** 로 사용 → Debezium의 두 쓰임새를 모두 보여줌.
- 로컬 메모리 부담(ES 1~2GB+) 때문에 나중 단계. 라이선스가 신경 쓰이면 OpenSearch(Apache 2.0).

### 프론트엔드 (후속 2, 인증과 함께)

- **Next.js + Tailwind + shadcn/ui.** 템플릿(`hexagonal-spring-next-template/frontend`)이 이미 Next.js 16 + Tailwind 4라 그대로 가져와 쓴다.
  - MUI도 가능하지만 Tailwind와 스타일 체계가 겹치고, App Router에서 Emotion 설정(`@mui/material-nextjs`)이 추가로 필요하다. 관리자 표(DataGrid)가 많이 필요해지면 그때 재검토.
- 화면은 최소한만: 로그인, 상품 목록·검색, 주문하기, 내 주문, **관리자 Saga 상태 화면**(주문별 단계·보상 진행 상황 타임라인).
- Saga 상태 화면은 동작을 보여주기에 가장 좋다. 결제 실패 주입 → 보상이 진행되는 모습을 화면으로 보여줄 수 있다.
- 인증과 함께 후속 2에서 만든다 (2026-11-23 정식 범위로. 처음에는 선택이었다). 화면 목록은 PROGRESS "후속 2 설계 메모".

---

## 4. 저장소 구조 (Gradle 멀티모듈 모노레포)

- **모노레포 + 서비스별 독립 실행·배포.** MSA의 핵심은 저장소 개수가 아니라 배포 단위 독립성.
- 지켜야 할 것:
  - **서비스별 DB 분리.** 로컬은 Postgres 하나에 DB를 나눠도 되지만 다른 서비스 테이블에 절대 접근하지 않는다.
  - **도메인 모듈을 서비스끼리 공유하지 않는다.** 공유는 메시지 계약(스키마)과 최소 공통 라이브러리까지.
  - 서비스별 이미지 빌드, CI 경로 필터로 바뀐 서비스만 빌드.

```
<repo>/
├─ services/
│   ├─ order-service/          # domain / application / adapter (헥사고날)
│   ├─ inventory-service/
│   ├─ point-service/
│   ├─ payment-service/
│   └─ notification-service/
├─ libs/
│   └─ messaging-contract/     # 커맨드·이벤트 메시지 스키마만
├─ infra/
│   ├─ docker-compose.yml      # Postgres(wal_level=logical), Kafka(KRaft), Kafka Connect + Debezium, Kafka UI
│   └─ connectors/             # Debezium 커넥터 JSON
├─ scripts/                    # 커넥터 등록, 부하 생성(k6), 장애 주입
├─ docs/
│   ├─ adr/                    # 설계 결정 기록
│   └─ scenarios/              # 시나리오별 재현 방법·결과
└─ README.md
```

### 헥사고날 템플릿 재사용 방침
- 기존 `C:\intelljProject\hexagonal-spring-next-template`는 **단일 백엔드 + Next.js 프론트** 구조라 "Use this template"으로 통째로 쓰지 않는다.
- **새 레포를 만들고 템플릿에서 필요한 것만 가져온다.**
  - 가져올 것: 서비스 내부 패키지 구조(`domain` / `application` / `adapter`, 샘플 `item` 참고), `common`(error, web, config), Gradle Kotlin DSL 설정·버전, Flyway 구성, `infra/observability`(후속 1), `.github`(CI, CodeQL, Dependabot, PR 템플릿), `AGENTS.md` 리뷰 지침 형식, Codex 리뷰 워크플로(drive의 `codex-review-on-push.yml`, 시크릿 `CODEX_TRIGGER_PAT`)
  - 새로 만들 것: 루트 멀티모듈 `settings.gradle.kts`(services/*, libs/*), Kafka·Debezium·Saga·outbox 관련 전부
  - 나중에 가져올 것: `frontend/`(Next.js + Tailwind, 프론트엔드 단계에서), openapi 기반 프론트 타입 생성. 1~3단계에서는 가져오지 않는다.

### 기술 스택 (템플릿과 맞춤)
- Java 25, Spring Boot 4.1, Gradle Kotlin DSL, PostgreSQL, Kafka (KRaft), Spring for Apache Kafka, Debezium (Kafka Connect), Flyway
- 테스트: JUnit 5, **Testcontainers** (Postgres + Kafka + Debezium 통합 테스트 1~2개)
- 관측성(후속 1): Prometheus/Grafana, OpenTelemetry (HTTP → Kafka → 컨슈머 트레이싱)
- k8s(후속 3): Strimzi (`Kafka`, `KafkaConnect`, `KafkaConnector` CRD), ArgoCD, Helm

---

## 5. 진행 단계

**1~3단계가 완성 기준이다.** 3단계가 끝난 `v0.3-reliability`에서 이 프로젝트의 주제(분산 정합성)는 다 보인다. 그 뒤는 후속 작업으로, 순서대로 하되 어디서 멈춰도 포트폴리오가 성립한다.

| 순서 | 단계 | 내용 | 태그 |
|---|---|---|---|
| 1 | 기본 흐름 | 모노레포 뼈대, docker-compose, order → inventory 정상 흐름 하나, outbox + 스케줄러 relay. 사용자는 `X-User-Id` 헤더로 대체 | `v0.1-basic-flow` |
| 2 | Saga 완성 | point, payment 추가, 실패 시 보상 트랜잭션, Saga 상태 머신, 코레오그래피 소비자(notification) | `v0.2-saga` |
| 3 | 신뢰성 | 멱등 컨슈머, 제자리 재시도 + DLT 재처리, Debezium relay + 스케줄러와 비교 측정, 타임아웃·장애 복구, 장애 주입 시나리오 문서화 | `v0.3-reliability` = **완성** |
| 후속 1 | 관측성 | 분산 트레이싱으로 Saga 한 건을 서비스 넘어 추적, consumer lag·DLT·slot 적체·결제 `UNKNOWN` 경보 (템플릿의 `infra/observability`) | |
| 후속 2 | 인증 + 프론트엔드 | api-gateway, auth-service(RS256 JWT + JWKS, refresh token 회전), 역할·소유권 검사, Redis(refresh token, 주문 멱등 키, rate limit), Next.js 화면 전체(로그인 포함) | |
| 후속 3 | k8s | Helm, Strimzi, ArgoCD. 클러스터를 상시 띄우지 않고 CI에서 kind로 검증 | |
| 후속 4 | 검색 | product-service + Debezium 테이블 CDC + search-indexer + Elasticsearch | |

- 순서는 2026-11-22에 정했다. 처음 계획은 4 인증 → 5 검색 → 6 운영(관측성·k8s)이었는데, 3단계에서 만든 메커니즘을 운영에서 보이게 하는 관측성을 먼저 하고, k8s·검색은 주제(정합성)와 거리가 있어 뒤로 미뤘다
- 프론트엔드는 처음에는 선택이었고 2026-11-23에 후속 2의 정식 범위가 됐다 (로그인을 포함한 화면 전체)
- 1~2단계로 MSA, 분산 트랜잭션, Kafka, 3단계에서 Debezium이 들어간다
- 후속까지 다 하면 서비스 9개(order, inventory, point, payment, notification, auth, gateway, product, search-indexer). 로컬 메모리 고려
- 후속 작업의 태그 이름은 시작할 때 정한다

---

## 6. 보여줄 시나리오 (`docs/scenarios/`)

각 시나리오는 **재현 명령 → 기대 결과 → 실제 결과(로그·스크린샷·숫자)** 형식.

1. 결제 실패 → 포인트 환불 → 재고 해제 (보상 순서 검증)
2. 결제 타임아웃 (애매한 상태) → 조회 후 보정
3. 중복 전달: 컨슈머 오프셋 되감기 → 재고·포인트 중복 반영 0건
4. 서비스 다운 중 주문 → 복구 후 Saga 이어서 진행
5. Debezium 커넥터 중단 → 재시작 후 유실 0건 + 중단 중 `pg_replication_slots` WAL 적체 관찰, heartbeat·모니터링으로 대응
6. poison message → DLT 격리 → 재처리 (retry 토픽 없이, ADR 0012)
7. 스케줄러 relay vs Debezium relay: Saga 완료까지 지연, DB 부하 비교 (k6 대신 bash + curl)
8. 알림 서비스 다운 → 주문은 정상 완료 (장애 격리)
9. (후속 2) 남의 주문 ID로 조회·취소 → 403 (IDOR 방지), refresh token 재사용 감지 시 전체 세션 폐기
10. (후속 2) 같은 `Idempotency-Key`로 주문 연속 요청 → 주문 1건만 생성

---

## 7. ADR 후보 (`docs/adr/`)

- 0000 왜 MSA인가 (이 규모는 모놀리스로 충분하지만 분산 정합성을 검증하려고 나눔, 메인 Saga 방식 대안 비교)
- 0001 모노레포 + 서비스별 독립 배포를 택한 이유
- 0002 영속성 방식 (JPA 분리 / JPA 직접 / Spring Data JDBC·jOOQ 중 선택)
- 0003 메인 Saga는 오케스트레이션, 후속 처리는 코레오그래피
- 0004 Outbox relay: 스케줄러 → Debezium 전환 (측정 근거 포함)
- 0005 Kafka 메시지 키 = orderId, 커맨드/이벤트 토픽 분리 규칙
- 0006 멱등 컨슈머 구현 방식
- 0007 결제 타임아웃 보정 전략
- 0008 인증: 자체 JWT(RS256 + JWKS) vs Spring Authorization Server vs Keycloak
- 0009 Redis 사용 범위 (재고를 Redis에 두지 않는 이유 포함)
- 0010 상품 검색 CQRS: Debezium 테이블 CDC → Elasticsearch
- 0011 프론트엔드: Tailwind + shadcn/ui (MUI 대신)
- 0012 실패 처리: 제자리 재시도 + DLT (retry 토픽 미사용)
- 0013 관측성: outbox 를 거쳐도 trace 문맥 잇기, 메트릭·경보 (후속 1)

---

## 8. 포트폴리오 운영 방식

- **레포는 하나.** 연습 레포를 따로 두지 않고 처음부터 이 레포를 포트폴리오로 키운다. 과정은 커밋·PR·태그로 남긴다.
- 지저분한 스파이크(Debezium 동작 확인 등)는 로컬 임시 폴더에서 하고 푸시하지 않는다.
- README 첫 화면:
  1. 이 저장소가 검증하는 문제 3줄
  2. 아키텍처 다이어그램
  3. 실행 방법 3줄 (`docker compose up` + 커넥터 등록 스크립트 + 서비스 실행)
  4. 시나리오 결과 요약표 (링크)
  5. 주요 설계 결정 (ADR 링크)

---

## 9. 작업 컨벤션

- 커밋·PR은 사용자(klmin) 이름으로, Claude attribution 없음.
- PR 단위 작업, 리뷰 코멘트·문서는 한국어.
- 설정 파일·테스트 코드에 실제 키·비밀번호를 넣지 않는다 (로컬 개발용 값만).
