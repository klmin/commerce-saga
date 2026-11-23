# 진행 현황과 다음 작업

이어서 작업할 때 이 문서부터 본다. 설계 배경은 [브리프](commerce-msa-portfolio-brief.md), 결정 이유는 [ADR](adr/), 시나리오별 재현·결과는 [scenarios](scenarios/), 작업·리뷰 규칙은 [AGENTS.md](../AGENTS.md).

- 마지막 갱신: 2026-11-23 (PR #22 머지 후, 3-C-2 PR)
- 현재 위치: **3단계(신뢰성) 진행 중. 3-A(+보강), 3-B, 3-C(Debezium relay, 시나리오 5·7, ADR 0004) 완료, 다음은 3-D(타임아웃·장애 복구)**

## 0. 새 세션(다른 PC·클라우드)에서 이어받을 때

이 저장소만 받으면 이어서 할 수 있게 정리해 둔 절이다. 이전 대화 기록은 넘어가지 않으므로 여기 적힌 것이 전부다.

**시작 순서**
1. 이 문서 → [AGENTS.md](../AGENTS.md)(코드·리뷰 규칙) → 할 작업에 걸린 ADR 순서로 읽는다
2. 다음 작업은 아래 4절 **3-D**. main에서 `feat/saga-timeout` 같은 브랜치를 만든다

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
| 3 | **신뢰성** (멱등 컨슈머, DLT 재처리, Debezium relay, 타임아웃·장애 복구) | **진행 중**. 끝나면 `v0.3-reliability` = **완성 기준** |
| 후속 1 | 관측성 (분산 트레이싱으로 Saga 한 건을 서비스 넘어 추적, lag·DLT 경보) | 3단계 후 |
| 후속 2 | 4단계 인증 + 프론트엔드 (auth-service 자체 JWT, Spring Cloud Gateway, Next.js 화면 전체). 설계는 아래 "후속 2 설계 메모" | 관측성 다음 |
| 후속 3 | k8s (Helm, Strimzi, ArgoCD). 클러스터 상시 구동 대신 CI에서 kind로 검증 | 나중으로 미룸 |
| 후속 4 | 상품 검색 (product-service + 테이블 CDC + Elasticsearch) | 나중으로 미룸 |

후속 순서는 2026-11-22에 사용자와 합의했다. 2026-11-23에 후속 2에 프론트엔드(로그인 포함 화면 전체)를 정식 범위로 넣었다 (전에는 선택). 브리프 5절은 아직 옛 순서(4~6단계 필수)라 3-E에서 고친다.

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
- Saga: `RESERVING_STOCK → USING_POINT → PAYING → COMPLETED`, 실패 시 `REFUNDING_POINT → RELEASING_STOCK → CANCELLED` (ADR 0003)
- 모든 발행은 outbox (비즈니스 변경 + outbox = 한 트랜잭션). relay는 두 모드: **스케줄러**(`libs/outbox`, `FOR UPDATE SKIP LOCKED`, 기본) 또는 **Debezium**(Kafka Connect + Outbox Event Router, `OUTBOX_RELAY_ENABLED=false`). **운영 기준은 Debezium, 코드 기본값은 스케줄러**(Connect 없이 잘못 띄워도 메시지를 잃지 않게) (ADR 0004)
- 메시지: key = orderId, value = payload JSON, 헤더 `id`(= outbox id) · `type` (ADR 0005)
- 멱등 두 겹: 같은 메시지 재전달은 `processed_message`, 내용이 같은 재요청은 주문별 기록 (ADR 0006)
- 실패: 일시 오류는 제자리 무한 재시도, 영구 오류는 `<토픽>.DLT` → `tools/dlt-replay`로 재처리. retry 토픽은 순서 때문에 안 씀 (ADR 0012)
- 내부 구조: 헥사고날 + DDD, 도메인 모델과 JPA 엔티티 분리, ArchUnit으로 의존 방향 검사 (ADR 0002). notification만 단순 구조

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
| (이번 PR) | 3-C-2: 시나리오 5(커넥터 중단 → 유실 0, slot WAL 적체, heartbeat)·7(relay 지연·DB 부하 측정), ADR 0004(운영 기준 Debezium, 코드 기본값 스케줄러), 커넥터 `poll.interval.ms=100`·heartbeat |

CI(`e2e`)가 매 PR마다 인프라 + 서비스를 실제로 띄워 시나리오 0·1·3·6·8과 IDOR 검사를 relay 두 모드(scheduler / debezium)에서 자동으로 돈다. debezium 모드는 시나리오 5도 돌고, 두 모드 모두 끝에 시나리오 7 측정값을 로그로 남긴다.

멱등 선점 원리(3-A 보강에서 테스트로 증명): 트랜잭션 첫 줄의 `insert ... on conflict (message_id) do nothing`이 선점 역할을 한다. 늦은 쪽은 앞 트랜잭션이 끝날 때까지 PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건(건너뜀), 롤백하면 1건(이어받아 처리). 테스트는 앞 트랜잭션을 커밋 직전에 멈춰 두고 늦은 쪽이 실제로 끝나지 않고 기다리는지까지 확인한다.

## 4. 남은 일 (3단계)

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
- 시나리오 7 (`scripts/measure-relay.sh`, CI 두 모드): k6 대신 bash + curl. 지연은 서버 시각 `orders.updated_at − created_at`. 진행 중 Saga 최대 5건으로 묶음 단위 투입, 통계 반영 대기 11초(Codex 지적 반영). 로컬 p50/p95 스케줄러 776/1271ms, Debezium(poll 100ms) 811/922ms, Debezium(poll 500ms 기본) 2449/3656ms. 유휴 커밋 19.6/s vs 0.8/s, 유휴 outbox 스캔 574 vs 0 (30초), 주문당 커밋은 비슷(15~17). 핵심 차이는 지연이 아니라 유휴 폴링 비용과 꼬리 지연
- 커넥터 `poll.interval.ms=100`: 기본 500ms로는 Debezium이 스케줄러보다 느렸다
- ADR 0004: **운영 기준은 Debezium, 코드 기본값(`outbox.relay.enabled=true`)은 스케줄러 유지**. Debezium 모드는 넣고 바로 지우므로 Connect 없이 그 모드로 뜨면 조용히 발행이 안 되기 때문. 배포 설정에서 `OUTBOX_RELAY_ENABLED=false`를 명시한다
- 로컬에서 겪은 것: 3-C-1 테스트로 넣은 `aggregatetype='x'` 행 하나 때문에 order 커넥터가 약 2.5시간 동안 아무것도 발행하지 못했는데 상태는 `RUNNING`이었다(Connect 프로듀서 `max.block.ms` 무한, 브로커 자동 생성 꺼짐). ADR 0004 위험 2. slot 적체 경보로 잡는다(후속 관측성)
- UUIDv7 주문 id는 앞 8자리(시각)가 같다. 로그에서 주문을 구분할 때는 뒤쪽을 본다

### 3-D. 타임아웃·장애 복구
- **멈춘 Saga 정리**: 같은 단계에 N분 넘게 있는 Saga를 찾아 커맨드 재전송(새 메시지 id, 참여 서비스는 주문별 기록으로 처음 결과 재응답) → 그래도 안 되면 역순 보상. 지금은 커맨드가 DLT로 가거나 응답이 안 오면 Saga가 그 단계에서 **계속 기다리는 구멍**이 있다
- **결제 타임아웃 보정**: 결과가 애매하면 바로 보상하지 않고 PG 조회(가짜 PG에 조회 API 추가) 후 결정
- **늦은 승인 자동 환불**: 타임아웃으로 이미 보상·취소했는데 PG 조회 결과가 승인이면 자동 환불 (drive 세션 제안 3)
- 서비스 다운 중 주문 → 복구 후 Saga 이어서 진행 (시나리오 4)
- 시나리오 2·4, ADR 0007

### 3-E. 정리 후 `v0.3-reliability` 태그
- 브리프 5절을 합의된 순서로 수정 (v0.3 = 완성, 후속 순서는 위 표)
- ADR 0000 "왜 MSA인가": 이 규모는 모놀리스로 충분하지만 분산 정합성을 검증하려고 나눴다 (drive 세션 제안 2)
  - 메인 Saga 방식의 대안 비교도 함께 적는다: ① 동기 API 오케스트레이션 + 이벤트 뒷처리 + 대사 배치(실무에서 가장 흔함, 대신 참여 서비스 장애가 곧 주문 장애) ② Temporal 같은 워크플로 엔진(Saga 상태·재시도·타임아웃을 엔진이 맡고 Kafka는 이벤트용으로 남음, 대신 이 프로젝트가 증명하려는 메커니즘이 엔진 뒤로 숨고 운영 인프라가 늘어남) ③ 순수 코레오그래피(흐름이 흩어져 보상 순서 관리가 어려움). 결론: 메커니즘을 직접 구현·증명하려는 목적과 장애 격리 때문에 Kafka 커맨드/응답 오케스트레이션을 택함. 머지된 ADR 0003은 고치지 않는다
- 시나리오 3·6 문서의 "CI 결과"를 PR #12·#13의 e2e 로그로 채우기 (#12: actions run `45033520460`)
- README 아키텍처를 3단계 기준으로, 시나리오 표·ADR 목록 정리

### 후속 2 설계 메모 (2026-11-23 사용자와 정리, 시작할 때 ADR로 남긴다)

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
- Spring Cloud Gateway: `/auth/**` → auth-service(검증 없이 전달), `/api/**` → JWT 검증 후 서비스별 라우팅, 사용자별 rate limit(Redis), 역할 검사(`/api/admin/**`). Boot 4.1에 맞는 Spring Cloud 릴리스를 시작할 때 확인
- 외부 공개는 edge(k8s 단계의 Ingress)만. Gateway·서비스·DB·Kafka·Redis는 내부망

**프론트엔드: Next.js + Tailwind + shadcn/ui** (브리프, ADR 0011 예정)
- 템플릿 `hexagonal-spring-next-template/frontend`(Next 16 App Router, 서버 액션, `server-only` API 클라이언트, openapi-typescript, zod env, Vitest·Playwright)를 가져와 shadcn을 더한다
- 구조: 템플릿 기본형(라우트 colocation) + 화면이 늘면 `features/<기능>/{api,components}`. FSD는 이 규모에 과해서 쓰지 않는다
- 로그인 화면은 Next. middleware가 쿠키를 보고 access 유효 → 통과, 만료 → refresh, 둘 다 없음 → `/login`
- 화면(포트폴리오용으로 상황에 맞게 전부): 회원가입·로그인·로그인 기기 관리, 주문 생성·목록·상세(Saga 단계 타임라인, 보상 경로 표시), 포인트, 결제 내역, 알림, 관리자(결제 장애 주입, DLT 조회·재처리 등)

## 5. 알려진 한계·나중 일

- 멈춘 Saga 자동 정리 없음 → 3-D
- `processed_message` 보존 기간 정리 작업 없음 (ADR 0006, 토픽 보존 + DLT 재처리 기간보다 길게)
- 스케줄러 relay를 여러 인스턴스로 띄우면 같은 키의 순서가 바뀔 수 있음 → Debezium 모드에서는 WAL 커밋 순서로 나가 해소 (ADR 0005)
- Debezium 모드에서 커넥터가 멈추면(또는 없는 토픽으로 라우팅되어 막히면) replication slot이 WAL을 붙잡아 디스크가 찬다. 관찰·heartbeat는 시나리오 5에서 끝냈고, slot 적체 경보는 후속 관측성 작업. `max_slot_wal_keep_size` 상한은 유실 위험 때문에 두지 않음 (ADR 0004)
- 결제 장애 주입 API(`PUT /admin/payment-faults`)는 인증 없음 → 4단계
- 분류되지 않은 영구 오류는 파티션을 막는다 (의도). lag 경보는 후속 관측성 작업에서

## 6. 작업 방식 (매 PR 동일)

1. main에서 `feat/…` 등 브랜치 → 작업 → 바뀐 모듈 빌드·테스트 (Docker 켜고 Testcontainers 포함)
2. e2e 시나리오가 있으면 로컬에서 한 번 돌려 본다
3. PR → **Codex 리뷰(지적 없음) + CI 전부 통과 후** squash 머지. 지적이 나오면 고쳐 push하면 재리뷰가 자동으로 돈다
4. 단계가 끝나면 태그 (`v0.3-reliability`)

커밋·PR은 `klmin` 이름, 한국어, Conventional Commits. 설정·테스트에 실제 비밀값 금지.

## 7. 로컬에서 이어서 돌리기

```bash
docker compose -f infra/docker-compose.yml up -d --wait     # Postgres, Kafka, Kafka UI(8989), 토픽·DB 초기화
./gradlew build                                             # 전체 빌드·테스트
./gradlew :services:inventory-service:bootRun               # 8082 (point 8083, payment 8084, order 8081, notification 8085)
scripts/smoke-saga.sh                                       # 시나리오 0·1 + IDOR
scripts/smoke-redelivery.sh snapshot|verify                 # 시나리오 3 (순서는 스크립트 머리말)
scripts/smoke-poison.sh                                     # 시나리오 6
scripts/dlt.sh list inventory.command.DLT                   # DLT 확인
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
