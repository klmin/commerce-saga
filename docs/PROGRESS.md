# 진행 현황과 다음 작업

이어서 작업할 때 이 문서부터 본다. 설계 배경은 [브리프](commerce-msa-portfolio-brief.md), 결정 이유는 [ADR](adr/), 시나리오별 재현·결과는 [scenarios](scenarios/), 작업·리뷰 규칙은 [AGENTS.md](../AGENTS.md).

- 마지막 갱신: 2026-11-24 (F-2a PR, PR #37 머지 후)
- 현재 위치: **3단계(신뢰성) 완료 = 완성 기준, 태그 `v0.3-reliability`. 후속 1(관측성) 완료(PR #27·#28). 후속 2(인증 + 프론트엔드) 진행 중: Boot 4.2(PR #29), A-1 auth-service(PR #30), A-2 Gateway·서비스 JWT 검증(PR #31) 완료, A-3-1 내 주문·결제 목록·주문 Idempotency-Key(PR #33), A-3-2 가입 → 포인트 계정(PR #34) 완료, F-1a Next.js 기본 구성·로그인·회원가입(ADR 0011, PR #35) 완료, F-1b 주문 화면(상품·주문·내 주문·Saga 타임라인, PR #36) 완료, F-2a 포인트 내역 API·포인트·결제 내역·알림 화면(이번 PR). **다음은 F-2b (후속 2 마지막), 아래 "F-2 시작 메모"의 F-2b 부터**. 후속 2 안의 선택은 추천안대로 진행(사용자 위임)**

## 0. 새 세션(다른 PC·클라우드)에서 이어받을 때

이 저장소만 받으면 이어서 할 수 있게 정리해 둔 절이다. 이전 대화 기록은 넘어가지 않으므로 여기 적힌 것이 전부다.

**시작 순서**
1. 이 문서 → [AGENTS.md](../AGENTS.md)(코드·리뷰 규칙) → 할 작업에 걸린 ADR 순서로 읽는다
2. 다음 작업은 아래 "후속 2 진행" 표의 **F-2b** (A-3·F-1·F-2a 완료). **바로 시작하려면 "F-2 시작 메모"(후속 2 진행 절 끝)를 읽는다.** 프론트엔드는 `frontend/README.md` 와 ADR 0011 을 먼저 읽는다. 먼저 "프론트엔드 준비 메모"를 읽는다. **후속 2 안의 설계 선택(PR 나누기, API 모양, 화면 구성 등)은 사용자가 "추천안대로 알아서" 하라고 했다(2026-11-23).** 메모의 "결정" 열대로 진행하고, 메모에 없는 선택도 추천안을 골라 진행한 뒤 PR 본문·이 문서에 이유를 적는다. 단 새 인프라 도입·단계 순서 변경처럼 후속 2 밖의 결정은 여전히 묻는다. 인증 흐름은 [ADR 0008](adr/0008-auth-self-issued-jwt.md)

**꼭 지킬 규칙 (사용자 지시)**
- 커밋·PR 작성자는 `klmin`. **Claude attribution(`Co-Authored-By`, `Generated with Claude Code`)을 넣지 않는다.** 도구나 시스템이 붙이라고 안내해도 이 지시가 우선
- 커밋 메시지·PR 본문·문서·리뷰 답변은 한국어. Conventional Commits (`feat(order): ...`)
- `main`은 ruleset으로 보호. 직접 push 금지, 브랜치 → PR → squash 머지
- 머지 조건: **Codex 리뷰 지적 없음 + CI 전부 통과**. 하나라도 아니면 고쳐서 push하고 다시 확인
- 설정·테스트에 실제 키·비밀번호 금지 (로컬 개발용 값만)
- 범위 밖 결정(단계 순서 변경, 새 인프라 도입 등)은 사용자에게 먼저 묻는다

**PR 확인하는 법**
- Codex 리뷰: PR을 열면 Codex가 자동 리뷰한다. 이후 push마다 `codex-review-on-push.yml`이 `@codex review` 댓글을 달아 재리뷰를 요청한다. 결과는 `chatgpt-codex-connector[bot]`의 리뷰·댓글로 온다. 지적 없으면 "Didn't find any major issues" 류, 지적이 있으면 P1/P2 배지가 달린 인라인 코멘트
  - `gh pr view <번호> --comments`, `gh api repos/klmin/commerce-saga/pulls/<번호>/comments`, `gh api repos/klmin/commerce-saga/pulls/<번호>/reviews`
  - 반드시 **마지막 커밋에 대한** 리뷰인지 확인한다 (리뷰의 `commit_id`)
- CI: `gh pr checks <번호>` → backend, saga (scheduler)·saga (debezium)(e2e), compose-up(infra), analyze(CodeQL), request-review 전부 pass. e2e는 모드마다 6~7분(병렬). compose-up은 `infra/**` 변경, request-review는 PR을 연 뒤 push가 있을 때만 뜬다
- 머지: `gh pr merge <번호> --squash --delete-branch`
- PR을 열면 앱의 Auto-fix(CI 실패·리뷰 코멘트가 오면 세션을 깨움)를 **항상 켠다** (Claude 데스크톱 앱 기능이라 AGENTS.md가 아니라 여기 적음). 머지는 Auto-fix가 조용해도 CI 통과와 마지막 커밋의 Codex 리뷰를 직접 확인한 뒤
- 머지 후 이 문서 3절 PR 표와 맨 위 "마지막 갱신·현재 위치"를 다음 PR에서 갱신한다

**환경 메모**
- Docker가 없는 환경이면 Testcontainers 테스트는 skip된다. 그때는 CI(backend, e2e)가 통합 검증을 대신하므로 CI 결과를 꼭 확인한다
- JDK 25는 Gradle 툴체인이 받는다. `java -jar`로 직접 띄울 때만 JDK 25 경로 필요 (7절)
- python3이 없을 수 있다. 스크립트는 bash + 기본 도구로 쓴다 (기존 `scripts/*.sh` 참고)

## 1. 목표와 범위 (합의된 것)

주제는 **분산 정합성**: 서비스를 나눈 뒤 주문·재고·포인트·결제가 실패·중복·지연·장애 속에서도 어긋나지 않는다는 것을 코드·테스트·시나리오 문서로 증명한다.

| 순서 | 단계 | 상태 |
|---|---|---|
| 1 | 기본 흐름 (모노레포, outbox + 스케줄러 relay, order → inventory) | 완료, 태그 `v0.1-basic-flow` |
| 2 | Saga 완성 (point, payment, 보상, 상태 머신, 알림 코레오그래피) | 완료, 태그 `v0.2-saga` |
| 3 | **신뢰성** (멱등 컨슈머, DLT 재처리, Debezium relay, 타임아웃·장애 복구) | 완료, 태그 `v0.3-reliability` = **완성 기준** |
| 후속 1 | 관측성 (분산 트레이싱으로 Saga 한 건을 서비스 넘어 추적, lag·DLT 경보) | 완료 (O-1 트레이싱 PR #27, O-2 메트릭·경보 PR #28) |
| 후속 2 | 인증 + 프론트엔드 (auth-service 자체 JWT, Spring Cloud Gateway, Next.js 화면 전체). 설계는 아래 "후속 2 설계 메모" | 진행 중 (Boot 4.2 PR #29, A-1 PR #30, A-2 PR #31, A-3 PR #33·#34, F-1a PR #35, F-1b PR #36. 다음 F-2) |
| 후속 3 | k8s (Helm, Strimzi, ArgoCD). 클러스터 상시 구동 대신 CI에서 kind로 검증 | 나중으로 미룸 |
| 후속 4 | 상품 검색 (product-service + 테이블 CDC + Elasticsearch) | 나중으로 미룸 |

후속 순서는 2026-11-22에 사용자와 합의했다. 2026-11-23에 후속 2에 프론트엔드(로그인 포함 화면 전체)를 정식 범위로 넣었다 (전에는 선택). 브리프 5절도 이 순서로 고쳤다(3-E).

## 2. 현재 아키텍처

```mermaid
flowchart LR
    client([client]) -- "POST /orders (X-User-Id)" --> order

    subgraph order-service [order-service · Saga 오케스트레이터]
        order[Order API] --> odb[(order_db\norders · order_sagas · outbox\nprocessed_message)]
    end

    order -- "*.command" --> kafka{{Kafka\n(KRaft)}}
    kafka -- "*.reply" --> order
    kafka --> inventory[inventory-service\ninventory_db]
    kafka --> point[point-service\npoint_db]
    kafka --> payment[payment-service\npayment_db · 가짜 PG]
    inventory & point & payment -- "*.reply" --> kafka
    order -- "order.events" --> kafka
    kafka -. 코레오그래피 .-> notification[notification-service]
    kafka -. 판매 확정 .-> inventory
    kafka -. "처리 불가 → *.DLT" .-> dlt[(DLT 토픽)]
    dlt -. "scripts/dlt.sh replay" .-> kafka
```

- 서비스 5개, 서비스마다 자기 DB·계정 (Postgres 하나에 DB 분리). 서비스끼리 HTTP 호출 없음, Kafka 메시지만
- Saga: `RESERVING_STOCK → USING_POINT → PAYING → COMPLETED`, 실패 시 `REFUNDING_POINT → RELEASING_STOCK → CANCELLED` (ADR 0003). 응답이 없으면 단계 타임아웃으로 재전송하다 포기 후 역순 보상, 결제는 `CANCELLING_PAYMENT`(`CancelPayment`)부터 (ADR 0007)
- 모든 발행은 outbox (비즈니스 변경 + outbox = 한 트랜잭션). relay는 두 모드: **스케줄러**(`libs/outbox`, `FOR UPDATE SKIP LOCKED`, 기본) 또는 **Debezium**(Kafka Connect + Outbox Event Router, `OUTBOX_RELAY_ENABLED=false`). **운영 기준은 Debezium, 코드 기본값은 스케줄러**(Connect 없이 잘못 띄워도 메시지를 잃지 않게) (ADR 0004)
- 메시지: key = orderId, value = payload JSON, 헤더 `id`(= outbox id) · `type` (ADR 0005)
- 멱등 두 겹: 같은 메시지 재전달은 `processed_message`, 내용이 같은 재요청은 주문별 기록 (ADR 0006)
- 실패: 일시 오류는 제자리 무한 재시도, 영구 오류는 `<토픽>.DLT` → `tools/dlt-replay`로 재처리. retry 토픽은 순서 때문에 안 씀 (ADR 0012)
- 내부 구조: 헥사고날 + DDD, 도메인 모델과 JPA 엔티티 분리, ArchUnit으로 의존 방향 검사 (ADR 0002). notification만 단순 구조
- 트레이싱: OTel(OTLP) → Tempo, Grafana로 조회. outbox `traceparent` → Kafka 헤더 → 리스너 observation 으로 주문 한 건 = trace 하나 (ADR 0013)

## 3. 지금까지 한 일 (PR)

| PR | 내용 |
|---|---|
| #1~#6 | 1단계: 뼈대·CI·AGENTS.md, 인프라 compose, 메시지 계약·outbox, order·inventory 기본 흐름, 시나리오 0 |
| #7~#11 | 2단계: 메시지 계약 확장, 재고 해제·확정, 포인트, 가짜 PG(장애 주입), Saga 오케스트레이터·보상, 알림, 시나리오 1·8 |
| #12 | 3-A 멱등 컨슈머 (`processed_message`), 오프셋 되감기 스크립트, 시나리오 3, ADR 0006, 스키마 호환 규칙(ADR 0005) |
| #13 | 3-B DLT 조회·재처리 CLI(`tools/dlt-replay`, `scripts/dlt.sh`), 시나리오 6, ADR 0012 |
| #14 | 이 문서 |
| #15 | 3-A 보강: 같은 메시지 동시 처리 통합 테스트 (inventory 커밋·롤백, point 커밋) |
| #16 | 이 문서에 새 세션 이어받기 안내(0절), 3-C를 두 PR로 나눔 |
| #17 | 브리프 목적 문구를 기술 중심으로 정리 |
| #18 | README 진행 상태를 3단계 기준으로 갱신 |
| #19 | 3-E에 ADR 0000 대안 비교 항목 추가 |
| #20 | 3-C-1 Debezium relay: Kafka 4.4.0, Kafka Connect + Debezium Outbox Event Router, 서비스별 커넥터·등록 스크립트, `OUTBOX_RELAY_ENABLED` 모드 전환, e2e matrix(scheduler/debezium) |
| #21 | 후속 2 범위에 프론트엔드 추가, 인증(자체 JWT·RTR)·Gateway·프론트 설계 메모 |
| #22 | 주문·결제·outbox(메시지) id 생성을 UUIDv4 → UUIDv7 (`uuid-creator`). 컬럼·메시지 계약 변경 없음, 사용자 id 는 bigint 유지 |
| #23 | 3-C-2: 시나리오 5(커넥터 중단 → 유실 0, slot WAL 적체, heartbeat)·7(relay 지연·DB 부하 측정), ADR 0004(운영 기준 Debezium, 코드 기본값 스케줄러), 커넥터 `poll.interval.ms=100`·heartbeat |
| #24 | 3-D-1: 멈춘 Saga 정리(단계 타임아웃 → 새 메시지 id로 재전송 → 정방향은 포기 후 역순 보상, 결제·보상은 계속 재전송), 시나리오 4(참여 서비스 다운), ADR 0007 |
| #25 | 3-D-2: 결제 타임아웃 보정(PG 타임아웃 → `UNKNOWN` → PG 조회로 확정), 결제 단계 포기 시 `CancelPayment`(새 상태 `CANCELLING_PAYMENT`, 응답 `PaymentCancelled`), 취소된 주문의 승인 결제 PG 환불(늦은 승인 자동 환불), 시나리오 2, ADR 0007 결제 부분 |
| #26 | 3-E 정리: 브리프 5절 진행 순서(1~3단계 = 완성, 후속 1~4), ADR 0000(왜 MSA인가, 메인 Saga 방식 대안 비교), 시나리오 3·6 CI 결과, README 아키텍처 3단계 기준 |
| #27 | 후속 1 O-1 분산 트레이싱: OTel(OTLP) → Tempo + Grafana, outbox `traceparent` 컬럼 → Kafka 헤더 → 리스너 observation(relay 두 모드 같은 방식), 리스너 span 에 orderId·메시지 타입, 시나리오 11, ADR 0013 |
| #28 | 후속 1 O-2 메트릭·경보: 서비스 `/actuator/prometheus`(Saga·결제·outbox·slot 메트릭, 자기 DB 기준), kafka-exporter(lag·DLT), Prometheus 경보 규칙 10개, Grafana 대시보드, 시나리오 12, ADR 0013 메트릭·경보 부분 |
| #29 | Spring Boot 4.1.2 → 4.2.0 (Kafka 클라이언트 4.4.0 = 브로커와 같은 버전, spring-kafka 4.2), Gradle 9.9.2. 후속 2 Gateway에 쓸 Spring Cloud 2026.0(Gateway 5.1)이 Boot 4.2 기준이라 먼저 올림. 시나리오 12 CI 결과 |
| #30 | 후속 2 A-1 auth-service: 회원가입·로그인(BCrypt, 아이디별 5회 실패 잠금), access token JWT RS256 + JWKS, refresh token `familyId.secret` 회전(Redis Lua compare-and-set)·동시 refresh 유예·재사용 탐지 시 family 폐기, 로그아웃·전체 로그아웃·로그인 기기 관리, Redis(6380), 시나리오 9(인증 부분), ADR 0008 |
| #31 | 후속 2 A-2: Spring Cloud Gateway(:8000, WebFlux, 라우팅·JWT 1차 검증·`/api/admin/**` ADMIN·사용자별 rate limit), 다섯 서비스 resource server(`X-User-Id` → JWT `sub`, `@CurrentUserId`), auth-service 개발용 시드 사용자, 스크립트 토큰화(`scripts/lib/auth.sh`), 시나리오 9 Gateway·소유권 부분(`smoke-gateway.sh`), 빌드 설정 분리(`boot-app-conventions`) |
| #32 | 문서: 후속 2 남은 계획(A-3 백엔드 보강 목록, F-1 템플릿 가져오기 결정·프론트엔드 준비 메모), 시나리오 9 CI 결과(PR #31) |
| #33 | 후속 2 A-3-1: 내 주문 목록 `GET /orders`·내 결제 목록 `GET /payments`(커서 페이징, DTO 직접 조회, 인덱스 `(user_id, id)`), 주문 `Idempotency-Key`(`order_idempotency_keys` 선점, 같은 키 = 처음 주문 200, 다른 본문 422), 시나리오 10(`smoke-idempotency.sh`, CI 두 모드) |
| #34 | 후속 2 A-3-2: auth-service outbox(V2) → `auth.events` `UserRegistered`(키 userId) → point-service `AuthEventsListener` 가 멱등하게 계정 생성(가입 축하 1,000P, `processed_message` + `on conflict (user_id) do nothing`), Debezium `auth-outbox` 커넥터·`auth_svc` replication, 시나리오 13(`smoke-signup-points.sh`, CI 두 모드), 시나리오 10 CI 결과(PR #33) |
| #35 | 후속 2 F-1a: 템플릿 `frontend/` 가져오기(items 예시·OpenAPI 생성 제거), 로그인·회원가입·로그아웃(Server Action), Next 서버가 토큰을 httpOnly 쿠키(`cs_access`·`cs_refresh`)로, `src/proxy.ts` 가 refresh(single-flight), 내 포인트 홈, CI `frontend`, Playwright 로컬 e2e, ADR 0011, 시나리오 13 CI 결과(PR #34). Codex 반영: Gateway 가 믿는 프록시(BFF)의 `X-Forwarded-For` 로 `/auth` rate limit(`ClientIpResolver`), 로그인 뒤 이동 경로 open redirect 강화 |
| #36 | 후속 2 F-1b: 홈 상품 1~5(고정 가격, 재고 조회), 주문하기(폼을 열 때 정한 `Idempotency-Key`), 내 주문(커서), 주문 상세(Saga 단계·기록, 진행 중 1초 갱신), Playwright 주문 시나리오 5개(`workers: 1`) |
| #37 | 문서: F-1b 완료, F-2 시작 메모(화면별 API 유무·PR 나누기·로컬 실행), DDD 점검과 후속 후보 |
| (이번 PR) | 후속 2 F-2a: point-service 포인트 내역 `GET /points/me/history`(주문 사용 + 지급 기록 `point_grants`, 커서 = UUIDv7 id 하나), 가입 축하 지급 기록, 화면 `/points`·`/payments`·`/notifications`, 시나리오 13d, Playwright 내역 시나리오 2개 |

CI(`e2e`)가 매 PR마다 인프라 + 서비스를 실제로 띄워 시나리오 0·1·2·3·4·6·8·9(인증·Gateway·IDOR)·10·11·12·13을 relay 두 모드(scheduler / debezium)에서 자동으로 돈다. debezium 모드는 시나리오 5도 돌고, 두 모드 모두 끝에 시나리오 7 측정값을 로그로 남긴다.

멱등 선점 원리(3-A 보강에서 테스트로 증명): 트랜잭션 첫 줄의 `insert ... on conflict (message_id) do nothing`이 선점 역할을 한다. 늦은 쪽은 앞 트랜잭션이 끝날 때까지 PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건(건너뜀), 롤백하면 1건(이어받아 처리). 테스트는 앞 트랜잭션을 커밋 직전에 멈춰 두고 늦은 쪽이 실제로 끝나지 않고 기다리는지까지 확인한다.

## 4. 3단계 작업 기록

### 3-C. Debezium relay

PR 두 개로 나눴다. 3-C-1(relay 교체, PR #20), 3-C-2(시나리오·ADR) 모두 완료.

**3-C-1에서 한 것 (결정·확인 사항)**
- Kafka 4.3.2 → 4.4.0: compose(kafka, kafka-init), 서비스·`tools/dlt-replay` Testcontainers, Kafka Connect를 한 버전으로. Dependabot PR #3은 이 PR 머지 후 닫는다
- Kafka Connect: Debezium 공식 이미지 대신 **`apache/kafka:4.4.0` + Debezium 3.7.1 Postgres 플러그인**(Maven Central, sha512 검증)으로 직접 빌드 (`infra/connect/Dockerfile`). Connect 런타임이 브로커와 같은 버전이 된다. 브로커를 올릴 때 이 FROM도 같이 올린다. 호스트 포트 8088(8083은 point-service)
- 커넥터: 서비스마다 하나(`infra/connect/<서비스>-outbox.json`, order·inventory·point·payment). slot `<서비스>_outbox_slot`, publication `<서비스>_outbox_pub`(filtered, outbox 테이블만). DB 비밀번호는 `${env:...}`(EnvVarConfigProvider)로 Connect 컨테이너 환경 변수에서 읽는다
- replication 권한: `infra/postgres/init`에서 서비스 계정에 `alter role … with replication` (up 때마다 다시 적용, 멱등). 접속 DB가 자기 DB로만 허용돼 다른 서비스 WAL은 못 읽는다
- 모드 전환: `outbox.relay.enabled`(`OUTBOX_RELAY_ENABLED`). false면 스케줄러가 꺼지고 `OutboxWriter`가 **같은 트랜잭션에서 넣고 바로 지운다**. Debezium은 WAL의 INSERT를 발행하고 DELETE는 Event Router가 버린다. 롤백되면 WAL에도 없다 (`OutboxWriterIntegrationTest`가 test_decoding slot으로 확인)
- 헤더: Connect 헤더 변환기를 `SimpleHeaderConverter`로 고정해 `id`·`type`이 **따옴표 없는 문자열**로 나간다(로컬에서 kafka-console-consumer로 확인). 그래서 컨슈머 코드는 그대로다. `JsonConverter`로 두면 `"\"…\""`가 되어 `type` 매칭까지 깨지므로 바꾸지 않는다. Debezium이 `__debezium.context.*` 헤더를 더 붙이지만 컨슈머는 무시한다
- `snapshot.mode=initial`: 커넥터를 처음 등록할 때 outbox에 남은 행(스케줄러 모드에서 아직 못 보낸 것)도 발행된다(로컬에서 확인). 그 행은 테이블에 남으므로 전환 후 확인하고 지운다
- e2e matrix `saga (scheduler)` / `saga (debezium)`: 같은 시나리오 0·1·3·6·8 + IDOR. debezium 모드는 끝에 outbox 0행 + 커넥터 RUNNING + slot 활성을 확인해 스케줄러가 아니라 Debezium이 발행했음을 보인다

**3-C-2에서 한 것 (결정·확인 사항)**
- 시나리오 5 (`scripts/smoke-connector-outage.sh`, CI debezium 모드): 커넥터 네 개 `stop` → 주문 5건은 `PENDING`, outbox 0행(메시지는 WAL에만), slot `active=f`·적체 증가 → `resume` 후 5건 `COMPLETED`, 포인트·재고 정확히 한 번 반영, 적체 감소
- heartbeat: `heartbeat.interval.ms=10000` + `heartbeat.action.query`로 `pg_logical_emit_message` (테이블·publication 변경 없음), 메시지는 `message.prefix.exclude.list=heartbeat`로 발행 안 함. heartbeat 토픽 `__debezium-heartbeat.<커넥터>`는 `kafka-init`이 만든다. 확인: 다른 DB에 WAL 20 MB를 만들면 모든 slot 적체가 21 MB → heartbeat 켠 커넥터만 20초 안에 96 B
- 시나리오 7 (`scripts/measure-relay.sh`, CI 두 모드): k6 대신 bash + curl. 지연은 서버 시각 `orders.updated_at − created_at`. 진행 중 Saga 최대 5건으로 묶음 단위 투입, 통계 반영 대기 11초(Codex 지적 반영). 로컬 p50/p95 스케줄러 776/1271ms, Debezium(poll 100ms) 811/922ms, Debezium(poll 500ms 기본) 2449/3656ms. 유휴 커밋 19.6/s vs 0.8/s, 유휴 outbox 스캔 574 vs 0 (30초), 주문당 커밋은 같음(CI 16.2 vs 16.1). CI p50/p95 스케줄러 1106/2277ms vs Debezium 821/1115ms. 핵심 차이는 지연이 아니라 유휴 폴링 비용과 꼬리 지연
- 커넥터 `poll.interval.ms=100`: 기본 500ms로는 Debezium이 스케줄러보다 느렸다
- ADR 0004: **운영 기준은 Debezium, 코드 기본값(`outbox.relay.enabled=true`)은 스케줄러 유지**. Debezium 모드는 넣고 바로 지우므로 Connect 없이 그 모드로 뜨면 조용히 발행이 안 되기 때문. 배포 설정에서 `OUTBOX_RELAY_ENABLED=false`를 명시한다
- 로컬에서 겪은 것: 3-C-1 테스트로 넣은 `aggregatetype='x'` 행 하나 때문에 order 커넥터가 약 2.5시간 동안 아무것도 발행하지 못했는데 상태는 `RUNNING`이었다(Connect 프로듀서 `max.block.ms` 무한, 브로커 자동 생성 꺼짐). ADR 0004 위험 2. slot 적체 경보(`ReplicationSlotLag`, 후속 1 O-2)로 잡는다
- UUIDv7 주문 id는 앞 8자리(시각)가 같다. 로그에서 주문을 구분할 때는 뒤쪽을 본다

### 3-D. 타임아웃·장애 복구

PR 두 개로 나눴다. 3-D-1(멈춘 Saga 정리, 시나리오 4, ADR 0007, PR #24), 3-D-2(결제 타임아웃 보정, 시나리오 2) 모두 완료.

**3-D-1에서 한 것 (결정·확인 사항)**
- order-service 단계 타임아웃 `saga.timeout.*` (기본 `step-timeout=30s`, `max-resends=3`, `scan-interval=5s`, 환경 변수 `SAGA_STEP_TIMEOUT` 등). 판단은 도메인 `OrderSaga.onTimeout`, 실행은 `SagaTimeoutService`, 주기 실행은 `adapter.in.scheduler.SagaTimeoutScheduler`(Debezium 모드에서도 돌도록 `@EnableScheduling`을 따로 켬)
- 재전송은 **새 메시지 id**(같은 id면 `processed_message`로 건너뛰어 응답도 안 옴). 참여 서비스는 주문별 기록으로 처음 결과를 다시 응답한다
- 정방향(재고 예약·포인트 차감)은 `max-resends` 넘으면 **반영됐다고 보고** 역순 보상. 포인트 단계에서 포기하면 `pointUsed`와 무관하게 환불부터. 결제(`PAYING`)와 보상 단계는 포기하지 않고 계속 재전송
- 스캔은 잠그지 않고 id만 읽고, Saga마다 행 락 후 `updatedAt`으로 다시 판단 → 응답과 겹치거나 인스턴스가 여럿이어도 한 번만 처리 (`SagaTimeoutServiceTest`)
- Flyway `V4__saga_timeout.sql`: `order_sagas.step_resends`, 진행 중 Saga 부분 인덱스 `(updated_at) where state not in ('COMPLETED','CANCELLED')`
- 시나리오 4 (`scripts/smoke-service-down.sh`, CI 두 모드): point-service를 `pkill`로 내리고 `java -jar`로 올린다. 4a 짧은 장애 → 재전송 후 `COMPLETED`, 포인트 한 번만 차감 / 4b 긴 장애 → 포기 → `REFUNDING_POINT` → 복구 후 밀린 `UsePoint` → `RefundPoint` 순으로 처리되어 `CANCELLED`, 포인트·재고 원복
- CI e2e는 order-service를 `SAGA_STEP_TIMEOUT=10s`, `SAGA_MAX_RESENDS=3`, `SAGA_TIMEOUT_SCAN_INTERVAL=1s`로 띄운다. 시나리오 5(커넥터 중단 약 20초)는 포기(약 40초) 전에 재개된다. 시나리오 7 유휴 커밋에는 이 스캔(1초에 한 번, order_db)이 더해진다
- 발견: 프로세스를 강제 종료하면 Kafka가 그 컨슈머를 `session.timeout.ms`(45초) 뒤에야 빼서, 다시 뜬 인스턴스가 파티션을 늦게 받는다(로컬 26초). 그사이 재전송을 다 쓰면 복구될 장애도 보상으로 끝난다. `step-timeout × (max-resends+1)`은 재기동 + 45초보다 길게 (ADR 0007, 시나리오 4 문서)

**3-D-2에서 한 것 (결정·확인 사항)**
- payment-service: PG 클라이언트 타임아웃(`fake-pg.client-timeout`, `PG_CLIENT_TIMEOUT`, 기본 3초)을 넘으면 결제를 `UNKNOWN`으로 기록하고 **응답하지 않는다**. 결제 보정(`PaymentReconcileService`, `payment.reconcile.*`, 기본 2초 주기)이 PG 조회로 승인·거절을 확정한 뒤 그때 응답한다. 거래가 `not-found-after`(기본 2분) 동안 없으면 거절로 확정한다
- 가짜 PG: 승인을 PG 쪽 스레드(가상 스레드)에서 처리해 클라이언트가 타임아웃으로 포기해도 계속 진행된다(`DELAY`). `inquire`·`refund` 추가, 원장 `refunded` 컬럼
- order-service: `PAYING`은 `max-payment-resends`(기본 10, `SAGA_PAYMENT_MAX_RESENDS`)까지 `Pay`를 다시 묻고, 넘으면 `CANCELLING_PAYMENT` → `CancelPayment`. `PaymentCancelled`를 받으면 포인트 환불 → 재고 해제 → `CANCELLED`(사유는 결제 타임아웃). 이 상태에서 늦게 온 승인·거절은 무시한다. 도메인 정책 값 객체 `SagaTimeoutPolicy`
- payment-service `CancelPayment`: PG를 부르지 않고 취소 요청만 기록하고 바로 응답한다. 결제가 없으면 `CANCELLED`를 남겨 늦게 온 `Pay`를 막는다. "취소 요청 + 승인"(처음부터 승인, 또는 `UNKNOWN`이었다가 늦게 승인 확인)은 보정 작업이 PG 환불 → `REFUNDED`로 바꾼다. 승인 응답은 보내지 않는다
- 메시지 계약 추가만: `CancelPayment`(payment.command), `PaymentCancelled`(payment.reply). 배포는 order-service 먼저(payment.reply의 모르는 타입은 DLT)
- Flyway payment `V4__payment_timeout.sql`: `payments.cancel_requested`·`updated_at`, 보정 대상 부분 인덱스, `fake_pg_transactions.refunded`. 결제 상태에 `UNKNOWN`·`CANCELLED`·`REFUNDED` 추가
- 시나리오 2 (`scripts/smoke-payment-timeout.sh`, CI 두 모드): 2a PG 지연 6초(> 클라이언트 3초) → `UNKNOWN` → 조회로 승인 → `COMPLETED` / 2b PG 지연 45초(> Saga 결제 대기 약 30초) → 결제 취소·포인트 환불·재고 해제 → `CANCELLED` → PG 늦은 승인 → 자동 환불(`REFUNDED`, 원장 `refunded=true`)
- CI e2e는 `SAGA_PAYMENT_MAX_RESENDS=2`, `PAYMENT_RECONCILE_INTERVAL=1s`를 더 둔다. 시나리오 7 유휴 커밋에 결제 보정 조회(1초에 한 번, payment_db)도 더해진다

### 3-E. 정리 후 `v0.3-reliability` 태그 (완료)
- 브리프 5절을 합의된 순서로 수정 (1~3단계 = 완성, 후속 1~4 표). 브리프의 다른 절도 옛 단계 번호(4·5·6단계)를 후속 번호로, 실패 처리(retry 토픽 → 제자리 재시도 + DLT), 시나리오 7 측정 도구(k6 → bash + curl)를 실제와 맞춤
- ADR 0000 "왜 MSA인가": 이 규모는 모놀리스로 충분하지만 분산 정합성을 검증하려고 나눴다. 메인 Saga 방식 대안 비교(① 동기 API 오케스트레이션 + 대사 배치 ② Temporal 같은 워크플로 엔진 ③ 순수 코레오그래피 vs Kafka 커맨드/응답 오케스트레이션). 머지된 ADR 0003은 고치지 않음
- 시나리오 3·6 문서의 CI 결과를 PR #12(run `45033520460`)·#13(run `45035791683`)의 e2e 로그로 채움
- README 아키텍처를 3단계 기준으로(relay 두 모드, 단계 타임아웃, `CancelPayment`·결제 보정, DLT), ADR 목록에 0000
- PR #26 머지 커밋(`a7ed503`)에 `v0.3-reliability` 태그를 붙였다

## 4-1. 후속 1. 관측성 (완료)

범위는 2026-11-23 사용자와 정했다: **트레이싱 + 메트릭·경보 둘 다**. PR은 둘로 나눈다. 작업은 한 곳(이 세션 또는 이어받은 한 PC)에서만 한다(같은 브랜치·이 문서를 두 곳에서 고치면 충돌).

**O-1 분산 트레이싱 (PR #27) 에서 한 것 (결정·확인 사항)**
- 서비스 공통: `spring-boot-starter-opentelemetry`(build-logic service-conventions). OTLP HTTP로 Tempo에 보낸다(`OTLP_TRACING_ENDPOINT`, 기본 `http://localhost:4328/v1/traces`), 샘플링 1.0(`TRACING_SAMPLING_PROBABILITY`). starter가 가져오는 OTLP 메트릭 export는 끔(O-2는 Prometheus가 긁는 방식)
- **outbox를 거쳐도 trace가 이어지게**: `outbox.traceparent` 컬럼(Flyway order·inventory·payment V5, point V4) ← `OutboxWriter`가 넣는 시점의 현재 span(`OutboxTraceContext`, Micrometer Tracing 있을 때만) → 스케줄러 relay가 헤더 `traceparent`로, Debezium은 Event Router `additional.placement`에 `traceparent:header:traceparent` → 받는 쪽 `spring.kafka.listener.observation-enabled=true`가 이어받음. relay는 span을 만들지 않는다
- **KafkaTemplate observation은 켜지 않는다**(켜면 relay 문맥으로 `traceparent`를 덮어써 끊김)
- 리스너 span에 `messaging.kafka.message.key`(orderId)·`messaging.message.type`(`MessageTracingConvention`, libs/outbox 자동 설정). Tempo에서 `{ span.messaging.kafka.message.key = "<orderId>" }`로 주문의 trace를 찾는다
- 인프라: `grafana/tempo:2.10.10`(단일 바이너리, 로컬 디스크, 컨테이너 내리면 사라짐) + `grafana/grafana:13.2.6`(익명 Admin, Tempo 데이터 소스 프로비저닝). 호스트 포트 Tempo API **3210**, OTLP **4328**, Grafana 3000 (이 PC에 다른 관측성 스택이 3200·4318을 쓰고 있어 비킴). Tempo 이미지는 셸·wget이 없어 healthcheck 없음 → CI는 `/ready`를 curl로 기다림. Tempo 3.x는 구조가 바뀌어(설정 미확인) 2.10을 씀
- 시나리오 11 (`scripts/smoke-tracing.sh`, CI 두 모드, 시나리오 2 다음): 요청에 traceparent를 넣고 정상 주문 = span 9개·서비스 5개 + 주문 id 검색으로 같은 trace, 결제 실패 = 보상까지 한 trace. 로컬 Debezium 모드 PASS
- 배포 순서: 서비스(마이그레이션) 먼저, 커넥터 설정 나중(`table.field.additional.missing=error`라 컬럼이 없으면 커넥터 실패). 로컬에서 커넥터 재등록 직후 첫 주문은 재시작 때문에 10초 넘게 걸려 타임아웃 재전송이 한 번 났다
- 한계: 단계 타임아웃 재전송·결제 보정 후 응답·traceparent 없는 예전 메시지는 새 trace로 시작(주문 id 검색으로는 모임). JDBC span 없음

**O-2 메트릭·경보 (PR #28) 에서 한 것 (결정·확인 사항)**
- 서비스: `micrometer-registry-prometheus`(service-conventions) + actuator `prometheus` 노출. 서비스 메트릭은 이름 앞에 `commerce_`
  - order-service `SagaMetrics`: `commerce_saga_active{state,phase}`, `commerce_saga_oldest_active_age_seconds{state,phase}`(시작 후 나이), `commerce_saga_timeout_resends_total`, `commerce_saga_timeout_give_ups_total`
  - payment-service `PaymentMetrics`: `commerce_payment_unknown`, `..._oldest_unknown_age_seconds`(**`created_at`부터**: `updated_at`부터 재면 `CancelPayment`가 나이를 0으로 되돌려 경보가 안 울렸다, 시나리오 12에서 발견), `commerce_payment_refund_pending`, `..._oldest_refund_pending_age_seconds`(취소 요청 시각부터)
  - libs/outbox `OutboxMetrics`(자동 설정, 네 서비스): `commerce_outbox_pending_rows`, `..._oldest_pending_age_seconds`, `commerce_outbox_replication_slot_lag_bytes`(자기 DB slot만)
  - 헥사고날 그대로: `adapter.in.metrics`(MeterBinder) → `port.in` `Get*StatsUseCase` → `port.out` `Load*StatsPort` → 영속성 어댑터(JdbcClient 집계, 부분 인덱스와 같은 조건). 스크레이프마다 조회, 2초 캐시
- 인프라: `prom/prometheus:v3.15.1`(호스트 **9091**, 이 PC에 다른 Prometheus가 9090), `danielqsj/kafka-exporter:v1.11.0`(lag·DLT 끝 오프셋, Connect 그룹은 해석 못 해 오류 로그만), Grafana에 Prometheus 데이터 소스 + 대시보드 `commerce-saga` 프로비저닝. 서비스는 `host.docker.internal`(`extra_hosts: host-gateway`)로 긁음. relay 지연 히스토그램은 하지 않음(시나리오 7 측정으로 충분)
- 경보 규칙 10개(`infra/prometheus/rules/commerce.yml`, `promtool check rules` 통과): ServiceDown, SagaStuck, SagaCompensationStuck, SagaTimeoutGiveUp, PaymentResultUnknown, PaymentRefundPending, DeadLetterMessage, ConsumerLagHigh, OutboxBacklog, ReplicationSlotLag. 문턱값은 시연용으로 짧고 운영 값은 ADR 0013 표. Alertmanager(전달)는 두지 않음
- 시나리오 12 (`scripts/smoke-alerts.sh`, CI 두 모드, 시나리오 11 다음): 12a 깨진 메시지 → `payment.command.DLT` → `DeadLetterMessage`, 12b PG 지연 45초 → `SagaTimeoutGiveUp`·`PaymentResultUnknown` firing → 환불 후 해제. 경보가 이미 firing 중일 수 있어 메트릭 값 증가도 함께 확인. 로컬 Debezium 모드 PASS
- 테스트: `SagaStatsPersistenceAdapterTest`, `PaymentStatsPersistenceAdapterTest`, `OutboxWriterIntegrationTest`(OutboxMetrics)

### 후속 2 진행 (2026-11-23 사용자 합의: A-1·A-2·F-1·F-2, 이후 A-3 추가 제안)

| 순서 | PR | 상태 |
|---|---|---|
| 0 | Spring Boot 4.1.2 → 4.2.0 (Spring Cloud 2026.0 = Gateway 5.1 이 Boot 4.2 기준, 4.1 에 맞는 릴리스 없음) | 완료 PR #29 |
| A-1 | auth-service: 회원·로그인·JWT 발급·JWKS·refresh 회전·기기 관리, Redis, ADR 0008, 시나리오 9(인증) | 완료 PR #30 |
| A-2 | Spring Cloud Gateway(라우팅, JWT 1차 검증, `/api/admin/**` = ADMIN, 사용자별 rate limit) + 각 서비스 resource server(`X-User-Id` → JWT `sub`), 시나리오 9 IDOR 부분, e2e 스크립트를 로그인 토큰으로 | 완료 PR #31 |
| A-3-1 | order·payment 조회 보강: 내 주문 목록, 내 결제 목록, 주문 `Idempotency-Key`(브리프 시나리오 10, 문서·스크립트·CI) | 완료 PR #33 |
| A-3-2 | 가입 → 포인트 계정: auth-service outbox(`UserRegistered`, `auth.events`) → point-service 멱등 계정 생성(가입 축하 포인트), Debezium 커넥터 포함 | 완료 PR #34 |
| F-1a | Next.js 기본 구성(템플릿 가져오기, ADR 0011) + 로그인·회원가입·로그아웃·세션 refresh, CI `frontend` | 완료 PR #35 |
| F-1b | 상품 목록(고정 1~5), 주문 생성(Idempotency-Key), 내 주문 목록, 주문 상세(Saga 단계 타임라인·보상 경로) | 완료 PR #36 |
| F-2a | 포인트 내역 API(point-service) + 포인트·결제 내역·알림 화면 | 이번 PR |
| F-2b | 로그인 기기 관리 + 관리자(결제 장애 주입. DLT 는 화면 없이 안내, 준비 메모 #6) + 브라우저 e2e CI 판단 + 후속 2 마무리 | **다음 (마지막)**, "F-2 시작 메모" |

**A-1 auth-service (PR #30) 에서 한 것 (결정·확인 사항)**
- 새 서비스 `services/auth-service` (8086, `auth_db`/`auth_svc`, Redis 6380). 헥사고날 그대로, 기능 패키지 `auth` 하나. Kafka·outbox 없음(다른 서비스와 메시지를 주고받지 않음)
- API: `POST /auth/signup`·`login`·`refresh`·`logout`·`logout-all`, `GET /auth/sessions`, `DELETE /auth/sessions/{id}`, `GET /.well-known/jwks.json`. 기기 관리·전체 로그아웃은 auth-service 자신의 공개키로 access token 을 검증(Spring Security resource server)
- access token: RS256 15분, `iss`·`sub`·`roles`·`kid`. 서명 키 `AUTH_JWT_PRIVATE_KEY_LOCATION`(PKCS#8 PEM) + `AUTH_JWT_KEY_ID`, 비우면 기동 때 임시 키(로컬·CI). 키 교체는 `auth.jwt.retired-keys` 로 이전 공개키를 JWKS 에 남김
- refresh token: `familyId.secret`(UUIDv4 + 32바이트 base64url), httpOnly·Secure·SameSite=Lax·Path=/auth 쿠키로만. Redis 에는 secret SHA-256 해시만. 회전은 Lua compare-and-set, 동시 refresh 유예(기본 20s)는 **access token 만 주고 쿠키는 그대로**(메모의 "같은 새 토큰 다시 주기"는 새 secret 원문을 서버에 둬야 해서 바꿈). 유예 뒤 옛 secret 은 재사용 탐지 → 그 family 삭제(브리프의 "전체 세션 폐기"를 그 기기로 좁힘, ADR 0008)
- 수명: sliding 14일(쓸 때마다 연장), 절대 30일. 역할·정지 여부는 refresh 마다 DB 에서 다시 읽음
- 로그인: BCrypt, 비밀번호 8~64자·72바이트 이하, 아이디 영문 소문자·숫자·밑줄 4~30자(대소문자 무시). 없는 아이디도 더미 해시 비교. 아이디별 15분 안 5회 실패 → 15분 `429` + `Retry-After`
- (A-1 당시) 시드 사용자는 두지 않았다 → A-2 에서 개발용 시드를 더함. 가입하면 id 1 부터 나와 포인트 시드(사용자 1~4)와 맞는다. 관리자(ADMIN) 계정 만드는 법과 e2e 의 로그인 사용자는 A-2 에서 정한다
- 테스트 68개: `RefreshFamilyTest`, `RefreshTokenTest`, `UserTest`, `AuthServiceTest`(인메모리 포트), `AuthControllerTest`(쿠키 속성), `AuthIntegrationTest`(Postgres + Redis, JWKS 서명 검증, 동시 refresh 8개 → 회전 1번), ArchUnit
- 시나리오 9 인증 부분 `scripts/smoke-auth.sh` (CI 두 모드, 서비스 기동 직후, `AUTH_REFRESH_GRACE=2s`). 로컬 PASS. Prometheus 가 auth-service(8086)도 긁는다(`ServiceDown` 대상)

**A-2 Gateway·서비스 JWT 검증 (PR #31) 에서 한 것 (결정·확인 사항)**
- `services/gateway` (8000, 이 PC는 8080을 다른 앱이 씀): Spring Cloud Gateway 2026.0(Gateway 5.1) **WebFlux**. `RedisRateLimiter`가 리액티브 쪽이라 이렇게 했다. 빌드 설정은 `commerce.boot-app-conventions`(관측성·JWT 검증·테스트 공통)와 `commerce.service-conventions`(서블릿 + validation)로 나눴다
- 라우팅: `/auth/**`·JWKS → auth-service(토큰 없이), `/api/{orders,stocks,points,payments,notifications}/**` → 각 서비스(`StripPrefix=1`), `/api/admin/payment-faults` → payment `/admin/payment-faults`(ADMIN). 나머지 경로는 거부. 들어온 `X-User-Id`는 지운다
- rate limit: `/api/**`는 사용자(sub)별 초당 20·버스트 40, `/auth/**`는 IP별 초당 5·버스트 20(`GATEWAY_*` 환경 변수). IP는 TCP 주소(XFF 안 믿음)
- 서비스 다섯 개 모두 resource server(같은 JWKS·issuer, `roles` → `ROLE_*`). `@CurrentUserId long userId` = `@AuthenticationPrincipal`의 `sub`. `UserIdHeader` 삭제. 서비스마다 `SecurityConfig`(actuator만 열고, inventory는 `GET /stocks/**`도 열고, `/admin/**` = ADMIN). 공유 모듈로 빼지 않음(서비스 간 모듈 공유 금지)
- CSRF: 끄지 않고 "쿠키가 실린 상태 변경 요청"에만 검사(Gateway는 `/auth/**` 제외, auth-service가 직접 막음). CodeQL의 CSRF 경고를 피하려는 것이 아니라 Bearer 전용 API의 실제 위협 모델
- 개발용 시드 사용자: auth-service `db/dev-seed/R__dev_seed_users.sql`(repeatable). `user1`~`user4` = id 1~4(포인트 시드와 같은 id), `admin` = 100, 비밀번호 `local-dev-pass`. 가입 id는 1000부터. 운영은 `AUTH_FLYWAY_LOCATIONS=classpath:db/migration`. 로컬 `auth_db`에 A-1 때 가입한 사용자가 있으면 id가 겹쳐 시드가 건너뛰어지므로 `drop database auth_db` 후 다시 띄운다
- 스크립트: `scripts/lib/auth.sh`(`-H "$(auth 1)"`, 관리자 `auth 100`, 토큰 10분 파일 캐시 `TOKEN_CACHE_DIR`). 기존 시나리오 스크립트 9개를 모두 토큰으로 바꿨다(서비스 URL 직접 호출은 그대로). auth-service를 다시 띄우면(임시 키) 캐시를 지운다
- 시나리오 9f~9i `scripts/smoke-gateway.sh`(CI 두 모드, smoke-saga 다음, `GATEWAY_USER_RATE=5 GATEWAY_USER_BURST=10`). 로컬 Debezium 모드에서 smoke-gateway·smoke-saga·smoke-auth·smoke-tracing PASS
- 테스트: `GatewayRoutingTest` 6개(JDK HttpServer가 서비스·JWKS 흉내, Redis Testcontainers), 서비스 `@WebMvcTest`는 `@Import(SecurityConfig)` + `jwt()`로 바꾸고 토큰 없으면 401, 결제 장애 주입 ADMIN 확인 추가
- 한계(A-2 당시): 가입한 사용자는 포인트 계정이 없었다(포인트 시드 1~4만). → A-3-2 에서 `UserRegistered`(auth.events)로 해결

**A-3-1 order·payment 조회 보강, 주문 Idempotency-Key (PR #33) 에서 한 것 (결정·확인 사항)**
- 내 주문 목록 `GET /orders?before=<orderId>&size=`(Gateway `/api/orders`), 내 결제 목록 `GET /payments?before=<paymentId>&size=`(Gateway `/api/payments`, 정확 경로를 라우트에 추가). 알림과 같은 커서 페이징(`size` 1~50, 기본 20, 최신순, 다음 쪽은 마지막 id). 경로에 사용자 id 없이 토큰 `sub`로만 거른다
- 헥사고날 그대로: `port.in` `ListMyOrdersUseCase`·`ListMyPaymentsUseCase`(조회 DTO `OrderSummaryResult`·`PaymentSummaryResult`) → 조회 전용 서비스(`OrderQueryService`·`PaymentQueryService`) → `port.out` `Load*SummariesPort` → JdbcClient 어댑터. 주문 줄은 주문 id 목록으로 한 번에 읽어 쿼리 2개(N+1 없음). 결제 목록은 PG 거래 id를 내보내지 않는다. 결제 응답에 `updatedAt` 필드 추가
- Flyway order V6 / payment V6: `(user_id, id)` 인덱스, 기존 `(user_id)` 인덱스는 앞부분이라 삭제
- 주문 `Idempotency-Key`(선택, 1~100자 보이는 ASCII): `order_idempotency_keys (user_id, idempotency_key)` PK + `order_id`(orders FK, `deferrable initially deferred`: 키를 먼저 선점하고 주문을 나중에 넣으므로). 주문 트랜잭션 안에서 `insert … on conflict do nothing`으로 선점 → `processed_message`와 같은 원리로 동시 요청도 하나만 만든다. 같은 본문(줄은 상품 id 순 정렬·수량·단가·포인트의 SHA-256)이면 처음 주문을 `200` + `Idempotent-Replayed: true`, 다르면 `422`(새 `UnprocessableRequestException`). 키 선점 + 주문 + Saga + 커맨드 outbox = 한 트랜잭션
- `PlaceOrderUseCase.placeOrder`가 `PlaceOrderResult(order, created)`를 돌려준다(컨트롤러가 201/200 구분)
- 시나리오 10 `scripts/smoke-idempotency.sh`(CI 두 모드, smoke-gateway 다음, 사용자 3): 같은 키 두 번, 동시 5번, 다른 본문 422, 두 주문 COMPLETED·포인트 2건분만 차감, 목록 커서·다른 사용자에게 안 보임
- 테스트: `OrderServiceTest`(키 6개), `OrderQueryAndIdempotencyIntegrationTest`(Postgres, 같은 키 8개 동시 → 주문·Saga·키 각 1행, 목록 커서), `PaymentSummaryPersistenceAdapterTest`, 컨트롤러 테스트, `GatewayRoutingTest`(목록 경로)
- 한계: 키 보존 기간 정리 작업 없음. 처음 요청이 처리 중이면 같은 키 요청은 기다렸다가 결과를 받는다(409 즉시 반환 아님)

**A-3-2 가입 → 포인트 계정 (PR #34) 에서 한 것 (결정·확인 사항)**
- 메시지 계약(추가만): `UserRegistered(userId, registeredAt)`, 토픽 `Topics.AUTH_EVENTS = "auth.events"`(auth-service 소유). 키는 `userId`(주문과 상관없는 사용자 단위 사실이라 orderId 규칙의 예외, AGENTS.md 에 적음). 개인정보·비밀값은 싣지 않는다
- auth-service: `libs/outbox`·`messaging-contract`·Kafka 의존 추가, Flyway `V2__outbox.sql`(traceparent 포함, 다른 서비스와 같은 모양), `port.out` `UserEventPort` → `adapter.out.messaging` `UserEventOutboxAdapter`. `signUp` 에서 사용자 저장 직후 같은 트랜잭션으로 outbox. 구독하는 토픽은 없다. A-1 때 "Kafka·outbox 없음"이던 것을 바꿨다
- point-service: `AuthEventsListener`(이벤트 토픽: 관심 없는 타입 건너뜀, type 헤더 없음·본문·id·userId 이상은 DLT) → `OpenPointAccountUseCase` → `PointAccountService`(새 서비스 클래스, `PointService` 생성자는 그대로): `processed_message` 선점 → `PointAccount.open(userId, 가입 축하)` → `PointAccountPort.insertIfAbsent`(`on conflict (user_id) do nothing`). 이미 있는 계정(시드 등)은 그대로, 쓴 잔액을 되돌리지 않는다. 가입 축하 포인트 `point.account.signup-bonus`(`POINT_SIGNUP_BONUS`, 기본 1,000)
- 인프라: kafka-init 에 `auth.events`·`auth.events.DLT`·`__debezium-heartbeat.auth-outbox`, `infra/connect/auth-outbox.json`(slot `auth_outbox_slot`), Connect 에 `AUTH_DB_PASSWORD`, `auth_svc` replication 권한, `register-connectors.sh` 기본 목록에 추가, e2e Debezium 확인에 `auth_db` outbox 0행. 기존 로컬 볼륨은 `docker compose up` 때 postgres-init 이 권한을 다시 적용한다
- 시나리오 13 `scripts/smoke-signup-points.sh`(CI 두 모드, 시나리오 10 다음): 가입 → 몇 초 안에 1,000P 계정 → 300P 써서 주문 COMPLETED → 700P, 계정 행 1개·시드 그대로
- 테스트: `AuthServiceTest`·`AuthIntegrationTest`(outbox 행, 중복 가입은 행 없음. relay 는 `fixed-delay=1h` 로 돌지 않게), `PointAccountServiceTest`, `AuthEventsListenerTest`, `PointIntegrationTest`(Kafka 재전달·중복·늦은 이벤트)
- 시나리오 10 문서에 PR #33 CI 결과
- 한계: 가입 축하 지급은 `point_usages` 에 남지 않는다(F-2 포인트 내역 때 지급 기록을 따로). 가입 직후 계정이 생기기 전에 포인트를 쓰는 주문은 지금처럼 포인트 단계 실패 → 보상. 탈퇴·정지 이벤트 없음. 머지된 ADR 0008 의 "가입한 사용자에게 포인트 계정 없음" 한계는 고치지 않고 이 문서·시나리오 13 에 해결을 적는다

**F-1a Next.js 기본 구성·로그인 (PR #35) 에서 한 것 (결정·확인 사항)**
- F-1 을 둘로 나눴다(추천안): F-1a 기본 구성·인증(이번), F-1b 주문 화면(상품·주문 생성·목록·상세 Saga 타임라인). 인증이 모든 화면의 바탕이고 한 PR 에 다 넣으면 리뷰가 너무 커서
- 템플릿 `klmin/hexagonal-spring-next-template` main `4ca64fe` 의 `frontend/` 를 파일만 복사(메모의 `61c6ebc` 이후 `frontend/` 변경 없음). items 예시, `api:types`·`openapi-typescript` 제거. 응답은 zod 로 검증
- 설계는 ADR 0011: 브라우저는 Next 만, Next 서버만 Gateway(`BACKEND_URL` 기본 :8000). 토큰은 Next 가 httpOnly·Secure·SameSite=Lax 쿠키 `cs_access`(토큰 수명 − 30초)·`cs_refresh` 로. 로그인·가입(바로 로그인)·로그아웃은 Server Action
- `src/proxy.ts`(Next 16 의 Middleware): access 없고 refresh 있으면 auth-service refresh → 응답·이번 요청 모두 새 쿠키. 같은 refresh token 은 프로세스 안에서 한 번으로 묶고 결과를 10초 남김(옛 쿠키로 온 요청이 재사용 탐지에 걸리지 않게). 거절이면 쿠키 지우고 `/login?next=`. `next` 는 사이트 안 경로만
- 홈 = 내 포인트(가입 직후 계정이 없으면 "만드는 중" 안내). 헤더에 사용자 번호(토큰 `sub` 표시용)·로그아웃
- 개발 서버는 3001(3000 은 Grafana). `.claude/launch.json` 은 로컬용이라 커밋하지 않았다
- 확인(로컬, 인프라 + auth·point·gateway jar + `next dev`): 브라우저로 로그인 실패·성공·로그아웃, `document.cookie` 빈 문자열, 가입 → "만드는 중" → 1,000P. curl 로 proxy refresh → 새 쿠키 2개, 10초 안 옛 토큰 → 같은 결과, 유예 지난 옛 토큰 → 쿠키 삭제 + `/login?next=%2Forders`(auth-service "재사용, 그 기기 세션 폐기"). Playwright `e2e/auth.spec.ts` 2개 통과
- Playwright 첫 실행 실패: 로그인 실패 응답 뒤 React 가 폼을 초기화하는 시점이 테스트의 다음 입력보다 늦어 비밀번호가 지워졌다. 테스트가 새 화면에서 다시 로그인하게 고침(사람이 입력하는 속도에서는 생기지 않음)
- CI `frontend`: format·lint·typecheck·Vitest(33개)·`next build`·Docker 빌드. 로컬 Node 24.13 은 engines(`^24.15`) 경고만 나고 동작한다. CI 는 Node 26
- Codex 리뷰 반영: (P1) BFF 를 거치면 Gateway `/auth/**` IP rate limit 이 Next 주소 하나로 묶여 사용자끼리 버킷을 나눠 썼다 → Next 가 브라우저 IP 를 `X-Forwarded-For` 로 넘기고 Gateway 는 `gateway.trusted-proxies`(`GATEWAY_TRUSTED_PROXIES`, 기본 루프백)에서 온 연결일 때만 그 맨 오른쪽 값을 쓴다(`ClientIpResolver`, ADR 0008·0011). 배포에서는 Next 대역으로 두고 Next 앞에 프록시를 둔다. (P2) `/	/evil.example` 처럼 URL 파서가 지우는 문자로 open redirect → 제어문자·공백·역슬래시 거부 + 파싱한 origin 확인
- CI 재실행 1번: saga(scheduler) 인프라 기동에서 Maven Central 이 Debezium 플러그인 다운로드에 429 를 돌려줌(코드와 무관, 실패한 잡만 재실행해 통과)
- 한계: refresh 묶기는 Next 인스턴스 안에서만(인스턴스 사이는 백엔드 유예 20초). 브라우저 e2e 를 CI 에 넣을지는 F-2 끝에

**F-1b 주문 화면 (PR #36) 에서 한 것 (결정·확인 사항)**
- 화면: 홈 = 내 포인트 + 상품 1~5(이름·가격은 `src/lib/products.ts` 고정, 재고는 `GET /api/stocks/{id}`), `/orders/new` 주문(수량·포인트·결제할 금액), `/orders` 내 주문(커서 `?before=`, 20개씩), `/orders/[orderId]` 주문 상세(주문 내용 + Saga 단계 + 기록). 헤더에 "내 주문"
- 상품 가격: 준비 메모 #5 (a) 그대로. 고정 목록의 가격을 `unitPrice` 로 보낸다(폼 값이 아니라 서버 액션이 목록에서 읽는다). 서버가 가격을 정하지 않는 것은 한계(후속 4 product-service)
- 주문 `Idempotency-Key`: 폼을 열 때 서버가 UUID 를 정해 hidden 으로 둔다. 응답을 못 받은 실패(시간 초과)·검증 오류는 같은 키를 다시 쓰고, 같은 키에 다른 내용(422)일 때만 새 키. 버튼을 여러 번 눌러도 주문 한 건(Playwright 로 같은 폼 세 번 제출 → 목록에 1건)
- 폼 제출: 하이드레이션 뒤에는 `onSubmit` 에서 Server Action 을 직접 불러 React 의 제출 후 폼 초기화를 피한다(입력이 실패 뒤에도 남음). 하이드레이션 전 제출도 `action` 으로 Server Action 에 간다(처음에는 빠져 있어 GET 으로 키가 URL 에 실렸다. Playwright 로 잡아 고침)
- Saga 단계: `src/lib/orders/saga-view.ts` 순수 함수가 기록(history)의 응답 이벤트로 재고·포인트·결제·완료 각 단계를 완료·진행 중·실패·되돌림(무엇으로)·건너뜀으로 나눈다. 포인트 0 이면 포인트 단계, 결제 금액 0 이면 결제 단계를 건너뛴다(OrderSaga 와 같다). 취소 뒤 오지 않은 단계는 "진행 안 함". 기록은 정방향·재시도·실패·보상 색으로 구분하고 이벤트 이름·시각(ms)·사유를 그대로 보여 준다
- 진행 중이면 상세 화면이 1초마다 `router.refresh()`(최대 2분, 그 뒤 "다시 확인" 버튼). 끝나면 멈춘다
- 남의 주문·없는 주문은 백엔드가 같은 404 → `notFound()`. 로그인이 풀려 `authedRequest` 가 던지는 redirect 는 `unstable_rethrow` 로 삼키지 않는다(주문 액션, 재고 병렬 조회)
- 확인(로컬, 인프라 + 서비스 jar 6개 + `next dev`): 브라우저로 정상 주문(재고 → 포인트 → 결제 → 완료), 품절(재고 예약 실패 → 되돌릴 것 없이 취소), 포인트 부족(포인트 실패 → 재고 해제 → 취소), 결제 거절 주입(결제 실패 → 포인트 환불 → 재고 해제 → 취소), 결제 4초 지연 주입("결제 중"에서 새로고침 없이 "완료"로)
- Playwright `e2e/orders.spec.ts` 5개(정상·품절·포인트 부족 보상·중복 제출 1건·남의 주문). 시나리오마다 새로 가입해 시드 잔액에 기대지 않는다. 병렬로 돌리면 가입·로그인이 한 IP 로 몰려 Gateway `/auth` rate limit(초당 5)에 걸려 `workers: 1` 로 바꿨다. 가입 직후 포인트 확인이 가입 화면 안내 문구("1,000P 를 드립니다")에 잘못 맞던 것도 고침
- Vitest 55개(+ Saga 단계 8, 주문 입력 4, 주문 액션 6)
- Codex 반영(P2): 주문과 Saga 를 따로 읽는 사이 Saga 가 끝나면 주문이 PENDING 으로 남아 갱신이 멈췄다 → 둘 다 끝났을 때만 멈춘다
- 한계: 가격은 클라이언트(Next) 고정 목록. 진행 표시는 폴링(1초). 주문 취소 API 는 없다(Saga 가 끝나야 결과가 난다)

**F-2a 포인트·결제 내역·알림 (이번 PR) 에서 한 것 (결정·확인 사항)**
- 포인트 내역 API `GET /points/me/history?before=&size=`(Gateway `/api/points/me/history`, 기존 라우트 그대로). 다른 목록과 같은 커서 모양(`size` 1~50, 기본 20, 최신순). 항목: `entryId`(커서), `kind`(`ORDER` 또는 `SIGNUP_BONUS`), `orderId`(주문 사용만), `amount`, `status`(`USED`·`REJECTED`·`REFUNDED` 또는 `GRANTED`), `reason`, 시각
- 가입 축하 지급을 어떻게 보일지(시작 메모의 숙제): **지급 기록 테이블 `point_grants`(V5)를 두고 계정을 만든 트랜잭션에서 함께 INSERT**(처리 기록 + 계정 + 지급 기록 = 한 트랜잭션, 계정이 이미 있거나 0P 면 남기지 않음). 대안이던 "잔액 응답에 가입 축하 금액만 싣기"는 지급 종류가 늘면 다시 바꿔야 해서 버림
- 두 기록을 한 목록으로: 주문 id(order-service)와 지급 id(point-service) 모두 UUIDv7 이라 id 하나로 정렬·커서. 각 표에서 최신 size 개씩 읽어 `union all` 후 다시 size 개(인덱스 `(user_id, order_id)`·`(user_id, id)`). 두 서비스 시계가 조금 달라도 커서가 같은 id 라 쪽 사이에 빠지거나 겹치는 항목은 없다(순서만 수 ms 어긋날 수 있음)
- 사용자를 모르는 기록(사용 전에 온 환불, `user_id` null)은 누구의 내역에도 안 나온다. 거절(`REJECTED`)은 "사용 안 됨", 보상 환불(`REFUNDED`)은 "환불됨"으로 잔액 변화 0 으로 보여 준다(한 주문 = 한 줄)
- 화면: `/points`(잔액 + 내역, 주문 사용은 주문 상세 링크), `/payments`(결제 내역, 상태 5가지 문구), `/notifications`(알림, 숫자 커서). 헤더에 포인트·결제·알림. 문구·커서 검사는 `src/lib/history/` 순수 함수(Vitest 63개, +8). 잘못된 `?before=` 는 첫 쪽으로
- 확인(로컬, Debezium 모드 서비스 7개 + 커넥터 5개 + `next dev`): point-service 테스트 51개(Testcontainers 포함), 시나리오 13(13d 추가) PASS, user1 로 세 화면·다음 쪽 링크, Playwright 9개(새 `e2e/history.spec.ts` 2개: 가입 축하 → 주문 사용 → 결제 완료 → 완료 알림, 포인트 부족 → "사용 안 됨"·결제 없음·취소 알림)
- 이 PC 환경 메모: Node 22.12 에서는 Vitest 가 undici 로드에 실패한다(engines `^24.15`). `npx -p node@24 node node_modules/vitest/vitest.mjs run` 처럼 Node 24 로 돌렸다. Playwright 브라우저(1255)가 없어 설치된 Edge 를 `launchOptions.executablePath` 로 지정한 임시 설정으로 돌렸다(커밋 안 함)
- 한계: F-2a 전에 가입해 계정이 이미 있던 사용자는 지급 기록이 없다(로컬·CI 데이터만). 시드 사용자 1~4 의 시작 잔액도 지급 기록이 아니다(시드)

### F-2 시작 메모 (2026-11-24 정리, 여기서 바로 시작)

F-2 = 후속 2 마지막. 남은 화면: 포인트(잔액·내역), 결제 내역, 알림, 로그인 기기 관리, 관리자(결제 장애 주입). 범위·결정은 아래 "프론트엔드 준비 메모"를 따른다(#6 DLT 는 화면 없이 Kafka UI 링크·`scripts/dlt.sh` 안내, #7 포인트 내역 API 추가).

**화면별 백엔드 API (Gateway 기준)**

| 화면 | API | 있나 |
|---|---|---|
| 포인트 잔액 | `GET /api/points/me` | 있음 (홈에서 씀) |
| 포인트 내역 | point-service 에 내 `point_usages` 조회(커서 페이징, 다른 목록과 같은 모양) | F-2a 에서 추가: `GET /api/points/me/history` (지급 기록은 `point_grants`) |
| 결제 내역 | `GET /api/payments?before=&size=` | 있음 (A-3-1) |
| 알림 | `GET /api/notifications/me` (커서) | 있음 |
| 로그인 기기 관리 | `GET /auth/sessions`, `DELETE /auth/sessions/{id}`, `POST /auth/logout-all` (Bearer access token) | 있음 (A-1). Next 에서 부를 때는 `lib/auth/backend.ts` 처럼 `ClientInfo`(브라우저 IP)를 넘긴다 |
| 관리자 결제 장애 주입 | `GET/PUT /api/admin/payment-faults` (`mode`: APPROVE·DECLINE·DELAY, `delayMillis`), ADMIN 역할 | 있음. 메뉴는 토큰 `roles` 에 ADMIN 이 있을 때만 보이고, 권한 검사는 백엔드 |

**PR 나누기 (추천안, 그대로 진행)**
- F-2a (완료, 위 절): 포인트 내역 API(point-service, 백엔드 규칙대로 테스트·ArchUnit) + 포인트·결제 내역·알림 화면
- F-2b (**다음**): 로그인 기기 관리 + 관리자 결제 장애 주입 화면 + 브라우저 e2e 를 CI 에 넣을지 판단(준비 메모 "F-1 CI" 항목) + 후속 2 마무리(README. 태그를 붙일지는 사용자에게 묻는다)

**로컬 실행 (F-1b 때 쓴 방법)**
- 인프라: `docker compose -f infra/docker-compose.yml up -d --wait`
- 서비스: `./gradlew build` 후 `java -jar services/<이름>/build/libs/<이름>-0.0.1-SNAPSHOT.jar` (JDK 25. 이 PC는 `C:/Users/user/.jdks/openjdk-25.0.2`). 화면 전체에 필요한 것: auth 8086, point 8083, order 8081, inventory 8082, payment 8084, notification 8085, gateway 8000
- 화면: `cd frontend && npm run dev -- --port 3001` (3000 은 Grafana). Playwright: `E2E_BASE_URL=http://localhost:3001 npm run test:e2e` (한 worker)
- 관리자 토큰: `admin` / `local-dev-pass` 로 `POST localhost:8000/auth/login`. 결제 장애 주입은 확인 뒤 `{"mode":"APPROVE","delayMillis":0}` 로 되돌린다
- Redis·Kafka 컨테이너를 다시 띄우면 오래 떠 있던 gateway 가 `/auth` 요청에 응답하지 않은 적이 있다(F-1a). 그러면 gateway 를 다시 띄운다

**DDD 점검 결과와 후속 후보 (2026-11-24 사용자 질문. 후보는 결정 필요)**
- 이미 된 것: 서비스 = bounded context(DB 분리, 메시지로만 연결, 계약은 `messaging-contract`). 행위를 가진 애그리거트(`Order`·`OrderSaga`·`Stock`·`StockReservation`·`PointAccount`·`Payment`·`User`·`RefreshFamily`, setter 없음). 도메인 모델과 JPA 엔티티 분리(ADR 0002: 애그리거트는 순수 자바, `@Entity` 는 어댑터 안 package-private, ArchUnit 이 검사). 루트마다 리포지토리(포트), 다른 애그리거트는 ID 참조, 쓰기만 애그리거트·조회는 DTO
- 의도적 타협(바꾸지 않음): 재고 예약은 한 트랜잭션에서 여러 `Stock` + `StockReservation` 을 바꾼다(ADR 0002). 도메인 이벤트 객체 없이 애플리케이션 서비스가 outbox 포트에 쓴다. notification 은 단순 구조
- 후보 1 (중간 규모, 후속 2 밖이라 **시작 전에 사용자에게 묻는다**): 값 객체. 금액·포인트·수량·userId 가 `long`/`int` 라 "0 이상"·"포인트와 원화 구분" 규칙이 흩어져 있다 → `Money`·`Points`·`Quantity` 등. 추천 시점은 F-2 다음 별도 PR
- 후보 2 (작음): 컨텍스트 맵 + 용어집 ADR(서비스 관계: 오케스트레이터·참여자·순수 소비자·공개 이벤트. 용어: 예약·확정·해제, 차감·환불, 보상). F-2b 끝에 붙여도 된다

### 프론트엔드 준비 메모 (2026-11-23 정리, A-3·F-1 시작 전에 읽기)

**백엔드는 프론트에 아직 부족하다 (A-3).** A-2까지로 인증·Gateway·소유권은 끝났지만, 화면이 쓸 API 중 없는 것이 있다. 지금 있는 API(Gateway 기준): `POST /auth/signup|login|refresh|logout|logout-all`, `GET/DELETE /auth/sessions`, `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/{id}/saga`, `GET /api/stocks/{productId}`, `GET /api/points/me`(잔액만), `GET /api/payments/orders/{orderId}`, `GET /api/notifications/me`(커서 페이징), `GET/PUT /api/admin/payment-faults`

| # | 없는 것 | 왜 필요 | 제안 | 결정 |
|---|---|---|---|---|
| 1 | 내 주문 목록 (`GET /orders?before=&size=`) | 주문 목록 화면 | order-service, 알림처럼 커서 페이징, DTO 직접 조회 | A-3-1 |
| 2 | 가입한 사용자의 포인트 계정 | 지금은 시드 1~4만 계정이 있어, 새로 가입하면 포인트를 쓰는 주문이 실패한다 | auth-service 에 outbox 를 붙여 `UserRegistered`(`auth.events`, 키 = userId) → point-service 가 멱등하게 계정 생성(가입 축하 포인트). 분산 정합성 주제와도 맞음 | A-3-2 |
| 3 | 주문 `Idempotency-Key` (브리프 시나리오 10) | 결제 버튼 두 번·새로고침에도 주문 1건 | order-service 에 `(user_id, idempotency_key)` 유니크 + 같은 키면 처음 주문 반환. 시나리오 10 문서·스크립트 | A-3-1 |
| 4 | 내 결제 목록 (`GET /payments`) | 결제 내역 화면 | payment-service, 커서 페이징 | A-3-1 |
| 5 | 상품 카탈로그(이름·가격) | 상품 목록·주문 화면. 지금은 재고(수량)만 있고 **가격을 클라이언트가 보낸다**(`unitPrice`) | (a) 프론트에 상품 1~5 를 고정 목록으로 두고 가격 문제는 한계로 기록 (b) product 정보를 inventory 에 넣고 주문 시 가격을 서버가 정함(주문 Saga 변경 큼) (c) 후속 4(검색, product-service) 때 | **(a) 로 결정**: 프론트에 상품 1~5 고정 목록(이름·가격), 가격을 클라이언트가 보내는 문제는 README·PROGRESS 한계에 적는다 |
| 6 | 관리자 DLT 조회·재처리 API | 관리자 화면 | 지금은 CLI(`tools/dlt-replay`)뿐. HTTP 로 내려면 어느 서비스가 맡을지(새 admin 서비스 vs 도구를 서버로) 정해야 함 | **결정**: F-2 관리자 화면은 결제 장애 주입만, DLT 는 Kafka UI 링크와 `scripts/dlt.sh` 안내 |
| 7 | 포인트 사용 내역 | 포인트 화면 | point-service `point_usages` 를 내 것만 조회 | F-2 (point-service 에 조회 API 추가) |

A-3 은 두 PR: A-3-1(1·3·4, order·payment 조회와 멱등 주문) → A-3-2(2, 서비스 간 이벤트라 따로).

**그 밖에 정해 둔 것 (추천안)**
- `Idempotency-Key`: 헤더 필수 아님(없으면 지금처럼), 있으면 `(user_id, idempotency_key)` 유니크. 같은 키 + 같은 본문이면 처음 주문을 `200` 으로, 같은 키 + 다른 본문이면 `422`. 키 보존 기간 정리 작업은 한계로
- 목록 API 는 알림과 같은 커서 페이징(`before` = 마지막 id, `size` 1~50, 최신순). UUIDv7 id 라 시간순 정렬과 같다
- 가입 축하 포인트: 1,000P. `UserRegistered` 가 늦거나 중복돼도 계정은 하나(`user_id` 유니크, `processed_message`). 포인트 계정이 아직 없을 때 주문하면 지금처럼 포인트 단계 실패 → 보상
- F-1 CI: `frontend` 워크플로(lint, typecheck, Vitest, `next build`)만 PR 마다. Playwright 는 로컬 시나리오로 두고, 전체 스택 + Next 를 띄우는 CI e2e 는 F-2 끝에 판단
- F-1 화면 범위: 로그인·회원가입·로그아웃, 상품 목록(고정), 주문 생성, 내 주문 목록, 주문 상세(Saga 단계 타임라인·보상 경로). F-2: 포인트(잔액·내역), 결제 내역, 알림, 로그인 기기 관리, 관리자(결제 장애 주입)

**F-1 템플릿: 가져온다 (사용자 결정).** 사용자 공개 저장소 `klmin/hexagonal-spring-next-template` 의 `frontend/`(확인 시점 main `61c6ebc`, 2026-11-23)를 이 저장소 `frontend/` 로 **파일만 복사**한다(git 이력 X, 가져온 커밋은 ADR 0011 에 적음).
- 들어 있는 것: Next 16.4 App Router·서버 액션, React 19.3, `server-only` API 클라이언트(`src/lib/api/client.ts`, Problem Details → `ApiError`, 10초 타임아웃, 요청 시점 env), zod env(`src/lib/env.ts`, `BACKEND_URL`), Tailwind 4 + **shadcn/ui 이미 설정됨**(`components.json`, button·card·input·label·textarea), Vitest + Testing Library, Playwright(`e2e/`), ESLint·Prettier, Dockerfile, Node 22/24+
- 지울 것: 예시 `items` CRUD(`src/app/items`, `src/lib/api/items*`)
- **API 타입은 zod 로 직접 쓴다** (사용자 결정). 템플릿의 `api:types`(openapi-typescript, `../openapi/openapi.json`)는 우리 서비스에 OpenAPI 문서가 없어 스크립트·`schema.d.ts` 를 빼고, 응답을 zod 스키마로 검증한다(API 가 열 개 남짓)
- 템플릿 `frontend/AGENTS.md` 의 경고: Next 16 은 학습 데이터와 다르니 `node_modules/next/dist/docs/` 를 먼저 읽을 것
- 구조: 템플릿 기본형(라우트 colocation), 화면이 늘면 `features/<기능>/{api,components}` (설계 메모)
- `BACKEND_URL` = Gateway `http://localhost:8000`. Next 서버만 Gateway 를 부른다(브라우저는 Next 만). CORS 불필요

**F-1 인증 흐름 (ADR 0008 과 맞출 것)**
- 로그인: 서버 액션이 `POST /auth/login` → 받은 access token 과 응답 `Set-Cookie` 의 refresh token 을 **Next 가 브라우저에 httpOnly·Secure·SameSite=Lax 쿠키로** 심는다(localStorage 금지)
- API 호출: 서버에서 access 쿠키를 `Authorization: Bearer` 로 붙인다
- refresh: middleware(또는 서버 헬퍼)가 access 만료면 `POST /auth/refresh` 에 refresh 쿠키를 `Cookie: refresh_token=…` 로 넣고 **`X-Requested-With` 헤더를 반드시 붙인다**(없으면 403). 응답에 `Set-Cookie` 가 없으면(동시 refresh 유예) refresh 쿠키는 그대로 두고 access 만 바꾼다. 같은 사용자의 동시 refresh 는 single-flight 로 묶는다
- 401 이면 `/login`, 로그아웃은 `POST /auth/logout`(+ `X-Requested-With`) 후 두 쿠키 삭제
- 개발 사용자 `user1`~`user4` / `admin`, 비밀번호 `local-dev-pass` (README)
- F-1 e2e(Playwright)를 CI 에 넣을지(백엔드 전체 + Next 를 띄워야 함)는 F-1 에서 정한다

### 후속 2 설계 메모 (2026-11-23 사용자와 정리. 인증 부분은 ADR 0008 로 남김)

**인증: 자체 JWT auth-service (Spring Authorization Server는 쓰지 않음)**
- 로그인은 자체 회원(아이디·비밀번호) 하나, 클라이언트는 우리 Next 앱뿐이다. OAuth2/OIDC(SAS, Keycloak)는 외부 앱에 로그인 제공·소셜 로그인·여러 앱 SSO가 필요할 때 의미가 있어 지금은 과하다. password grant가 폐기돼 SAS로는 Next 화면에서 바로 로그인할 수 없다는 점도 이유. 필요해지면 서비스 쪽(resource server)은 그대로 두고 auth-service만 바꾼다
- auth-service (API만, 화면 없음, `auth_db` + Redis): `POST /auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout`(+ 전체 로그아웃), `GET /.well-known/jwks.json`. 비밀번호 bcrypt/argon2, 로그인 시도 제한
- access token: JWT RS256, 10~15분, `kid` 포함, 클레임 `sub`(userId) · `roles`. 개인키는 auth-service만. 키 교체 때 JWKS에 새 키·이전 키를 함께 노출
- 권한: **roles만** 쓴다(`users` ↔ `user_roles`, 예: `USER`, `ADMIN`). Gateway는 경로별 역할(`/api/admin/**` = ADMIN), 서비스는 `@PreAuthorize` + 소유권 검사. **OAuth scope는 쓰지 않는다**(외부 앱에 권한을 위임할 때 필요한 개념이라 우리 앱 하나에는 의미가 없음). roles는 refresh 때 DB에서 다시 읽어 넣어, 역할 변경이 AT 수명(최대 15분) 안에 반영되게 한다
- 사용자 id는 bigint 유지(서비스 테이블·메시지 `userId: long`과 맞춤). 외부 노출이 필요해지면 `public_id uuid` 컬럼 추가
- refresh token: **opaque `familyId.secret`**(JWT 아님, secret은 랜덤 32바이트), 14일, httpOnly 쿠키(`Path=/auth`)로만. 서버에는 원본을 두지 않는다(split token)
- Redis 키 두 종류: `family:<familyId>` = { userId, agent, `current` = hash(secret), createdAt, 절대만료 } (TTL 14일), `user:<userId>:families` = familyId 목록(Sorted Set, 점수 = 만료 시각)
  - **로그인**(최초·재로그인 모두): familyId·secret 새로 생성 → family 저장 → families에 추가. 다른 family는 건드리지 않는다(여러 기기 동시 로그인)
  - **refresh**: RT에서 familyId를 꺼내 family 조회 → 없으면 401 / hash(secret) == current면 새 secret으로 current 교체·TTL과 점수 연장, 새 RT = 같은 familyId.새 secret / **다르면 이미 회전된 옛 RT 재사용 = 탈취 의심 → family 삭제** / 절대만료(예: 30일) 지나면 삭제 후 재로그인
  - **로그아웃**: family 삭제 + families에서 제거. **전체 로그아웃·비밀번호 변경·계정 정지**: 사용자 family 전부 삭제
  - 만료된 familyId는 로그인·기기 목록 조회 때 `ZREMRANGEBYSCORE`로 정리. 로그인 저장(family + families)은 한 트랜잭션(MULTI 또는 Lua)
  - 비교는 상수 시간(`MessageDigest.isEqual`)
- 여러 탭 동시 refresh 오탐: Next 서버 single-flight + 짧은 유예(직전 해시를 10~30초만 `previous`로 남겨 같은 새 토큰을 다시 돌려줌)
- 이미 나간 access token은 짧은 수명으로 감수(즉시 차단이 필요하면 사용자 토큰 버전을 Gateway에서 확인하는 방식 검토)
- 검증: Gateway 1차(JWKS 캐시, 실패면 401) + 각 서비스 resource server 재검증(내부망 무조건 신뢰 안 함). 소유권 검사는 지금처럼 서비스 안에서, `X-User-Id` 대신 JWT `sub`와 비교. 일반 API 요청마다 auth-service를 부르지 않는다
- 토큰 보관: Next 서버가 httpOnly·Secure·SameSite=Lax 쿠키로만. 브라우저 JS(localStorage)에 두지 않는다

**Gateway·배포 경계**
- Spring Cloud Gateway: `/auth/**` → auth-service(검증 없이 전달), `/api/**` → JWT 검증 후 서비스별 라우팅, 사용자별 rate limit(Redis), 역할 검사(`/api/admin/**`). Spring Cloud 2026.0 (Gateway 5.1, Boot 4.2 기준). 그래서 후속 2 시작 전에 Boot 4.1.2 → 4.2.0 으로 올렸다
- 외부 공개는 edge(k8s 단계의 Ingress)만. Gateway·서비스·DB·Kafka·Redis는 내부망

**프론트엔드: Next.js + Tailwind + shadcn/ui** (브리프, ADR 0011 예정)
- 템플릿 `hexagonal-spring-next-template/frontend`(Next 16 App Router, 서버 액션, `server-only` API 클라이언트, openapi-typescript, zod env, Vitest·Playwright)를 가져와 shadcn을 더한다
- 구조: 템플릿 기본형(라우트 colocation) + 화면이 늘면 `features/<기능>/{api,components}`. FSD는 이 규모에 과해서 쓰지 않는다
- 로그인 화면은 Next. middleware가 쿠키를 보고 access 유효 → 통과, 만료 → refresh, 둘 다 없음 → `/login`
- 화면(포트폴리오용으로 상황에 맞게 전부): 회원가입·로그인·로그인 기기 관리, 주문 생성·목록·상세(Saga 단계 타임라인, 보상 경로 표시), 포인트, 결제 내역, 알림, 관리자(결제 장애 주입, DLT 조회·재처리 등)

## 5. 알려진 한계·나중 일

- 결제 취소 응답(주문 취소)과 실제 PG 환불 사이에 시간 차가 있다(보정 주기 + 늦은 승인까지). 환불이 계속 실패하면 경고 로그와 경보(`PaymentRefundPending`). `UNKNOWN`이 오래가면 `PaymentResultUnknown`
- 보상 단계가 계속 실패하면 끝없이 재전송하고 경고 로그와 경보(`SagaCompensationStuck`)
- `processed_message` 보존 기간 정리 작업 없음 (ADR 0006, 토픽 보존 + DLT 재처리 기간보다 길게)
- 스케줄러 relay를 여러 인스턴스로 띄우면 같은 키의 순서가 바뀔 수 있음 → Debezium 모드에서는 WAL 커밋 순서로 나가 해소 (ADR 0005)
- Debezium 모드에서 커넥터가 멈추면(또는 없는 토픽으로 라우팅되어 막히면) replication slot이 WAL을 붙잡아 디스크가 찬다. 관찰·heartbeat는 시나리오 5에서 끝냈고, slot 적체 경보는 `ReplicationSlotLag`. `max_slot_wal_keep_size` 상한은 유실 위험 때문에 두지 않음 (ADR 0004)
- 결제 장애 주입 API 는 ADMIN 역할만 (Gateway `/api/admin/payment-faults` + payment-service `/admin/**`, A-2)
- 인증: 이미 나간 access token 은 최대 15분 유효(즉시 차단 없음). 로그인 잠금은 아이디 기준이라 남이 내 계정을 15분 잠글 수 있음(IP 제한은 Gateway rate limit). 동시 refresh 유예(20초) 안의 옛 refresh token 은 access token 을 받을 수 있음(회전은 못 함) (ADR 0008)
- 분류되지 않은 영구 오류는 파티션을 막는다 (의도). `ConsumerLagHigh` 경보로 잡는다
- trace: 단계 타임아웃 재전송·결제 보정 후 응답은 원래 trace 에 잇지 못하고 새 trace 로 시작한다(주문 id 검색으로 모임). 이으려면 Saga 행에 처음 traceparent 를 저장 (ADR 0013)
- 경보 문턱값은 시연용(짧음), 경보 전달(Alertmanager)은 없음. 운영 값은 ADR 0013 표

## 6. 작업 방식 (매 PR 동일)

1. main에서 `feat/…` 등 브랜치 → 작업 → 바뀐 모듈 빌드·테스트 (Docker 켜고 Testcontainers 포함)
2. e2e 시나리오가 있으면 로컬에서 한 번 돌려 본다
3. PR → **Codex 리뷰(지적 없음) + CI 전부 통과 후** squash 머지. 지적이 나오면 고쳐 push하면 재리뷰가 자동으로 돈다
4. 단계가 끝나면 태그 (`v0.3-reliability`)

커밋·PR은 `klmin` 이름, 한국어, Conventional Commits. 설정·테스트에 실제 비밀값 금지.

## 7. 로컬에서 이어서 돌리기

```bash
docker compose -f infra/docker-compose.yml up -d --wait     # Postgres, Kafka, Kafka UI(8989), 토픽·DB 초기화, Tempo(3210, OTLP 4328)·Prometheus(9091)·Grafana(3000), Redis(6380)
./gradlew build                                             # 전체 빌드·테스트
./gradlew :services:inventory-service:bootRun               # 8082 (point 8083, payment 8084, order 8081, notification 8085, auth 8086, gateway 8000)
scripts/smoke-saga.sh                                       # 시나리오 0·1 + IDOR
scripts/smoke-redelivery.sh snapshot|verify                 # 시나리오 3 (순서는 스크립트 머리말)
scripts/smoke-poison.sh                                     # 시나리오 6
SAGA_STEP_TIMEOUT=10s SAGA_MAX_RESENDS=3 SAGA_TIMEOUT_SCAN_INTERVAL=1s ./gradlew :services:order-service:bootRun  # 단계 타임아웃을 짧게 (기본 30s·3·5s)
scripts/smoke-service-down.sh                               # 시나리오 4 (point-service 를 내렸다 올림. Windows 는 POINT_STOP_CMD·POINT_START_CMD)
PG_CLIENT_TIMEOUT=3s PAYMENT_RECONCILE_INTERVAL=1s ./gradlew :services:payment-service:bootRun   # 결제 보정 주기를 짧게
scripts/smoke-payment-timeout.sh                            # 시나리오 2 (order-service 는 SAGA_PAYMENT_MAX_RESENDS=2 도)
scripts/dlt.sh list inventory.command.DLT                   # DLT 확인
scripts/smoke-tracing.sh                                    # 시나리오 11 (trace 는 Grafana http://localhost:3000 → Explore → Tempo)
scripts/smoke-alerts.sh                                     # 시나리오 12 (경보는 http://localhost:9091/alerts, 대시보드는 Grafana commerce-saga)
AUTH_REFRESH_GRACE=2s ./gradlew :services:auth-service:bootRun   # 동시 refresh 유예를 짧게 (기본 20s)
GRACE_WAIT=3 scripts/smoke-auth.sh                          # 시나리오 9 인증 (가입·로그인·회전·재사용 탐지·잠금)
scripts/smoke-gateway.sh                                    # 시나리오 9 Gateway·소유권 (gateway 를 GATEWAY_USER_RATE=5 GATEWAY_USER_BURST=10 으로 띄우면 429 확인)
```

Debezium relay 모드로 돌리기 (infra 는 위와 같이 띄우면 connect 도 뜬다):

```bash
OUTBOX_RELAY_ENABLED=false ./gradlew :services:order-service:bootRun   # 네 서비스 모두 이렇게 (스케줄러 끄고 넣고 바로 지우기)
scripts/register-connectors.sh                              # 서비스가 한 번 떠서 outbox 테이블이 생긴 뒤. RUNNING 까지 기다림
scripts/register-connectors.sh status                       # 커넥터·태스크 상태 (Kafka UI 의 Kafka Connect 메뉴에서도 보인다)
scripts/smoke-connector-outage.sh                           # 시나리오 5 (커넥터 stop → 주문 → resume)
scripts/measure-relay.sh                                    # 시나리오 7 (두 모드에서 각각 돌려 비교)
```

- 커넥터 하나 멈추기·재개: `curl -X PUT localhost:8088/connectors/<이름>/stop` / `.../resume` (stop은 태스크를 내리고 slot이 비활성이 된다. pause는 태스크가 연결을 쥔 채 멈춘다)
- slot 적체: `select slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) from pg_replication_slots`
- 기존 로컬 볼륨에서는 `up -d`를 한 번 다시 해야 heartbeat 토픽이 생긴다(`kafka-init`). 토픽이 없으면 커넥터가 heartbeat 발행에서 막힌다
- 커넥터 설정(json)을 바꾼 뒤에는 `scripts/register-connectors.sh`를 다시 돌리면 된다(PUT, 멱등). STOPPED 상태에서는 먼저 `resume`

- 순서: 서비스 기동(Flyway가 outbox 생성) → 커넥터 등록 → 트래픽. 이 모드는 outbox를 넣고 바로 지우므로 **slot이 생기기 전에 쓴 메시지는 발행되지 않는다**. slot이 없으면 서비스가 기동 때 WARN을 남긴다(`DebeziumSlotCheck`)
- 커넥터를 등록한 채로 서비스를 스케줄러 모드로 띄우면 두 relay가 같은 메시지를 두 번 보낸다(id 같음 → 컨슈머가 걸러 결과는 같지만 의도한 상태가 아님). 모드를 바꿀 때는 커넥터를 지우거나(`curl -X DELETE localhost:8088/connectors/<이름>`) `down -v`로 새로 시작한다
- 커넥터를 지워도 slot은 DB에 남아 WAL을 붙잡는다. 다시 등록하지 않을 거면 `select pg_drop_replication_slot('<서비스>_outbox_slot')`

- 실행 jar(`java -jar`)는 JDK 25가 필요하다. PATH의 java가 더 낮으면 JDK 25 경로로 직접 실행하거나 `JAVA_HOME`을 지정한다 (`bootRun`은 Gradle 툴체인이 알아서 맞춘다)
- Windows Git Bash에서 `docker compose exec … /opt/kafka/…`는 경로가 윈도 경로로 바뀌므로 `MSYS_NO_PATHCONV=1` (스크립트에는 이미 들어 있다)
- 로컬 Kafka·DB에 이전 단계 데이터가 남아 있으면 시나리오 3이 "응답 재발행"으로 실패할 수 있다(ADR 0006 배포 경계). 깨끗이 다시 하려면 `docker compose -f infra/docker-compose.yml down -v` (로컬 데이터 삭제)
