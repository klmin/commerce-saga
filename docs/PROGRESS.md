# 진행 현황과 다음 작업

이어서 작업할 때 이 문서부터 본다. 설계 배경은 [브리프](commerce-msa-portfolio-brief.md), 결정 이유는 [ADR](adr/), 시나리오별 재현·결과는 [scenarios](scenarios/), 작업·리뷰 규칙은 [AGENTS.md](../AGENTS.md).

- 마지막 갱신: 2026-11-23 (PR #15 머지 후)
- 현재 위치: **3단계(신뢰성) 진행 중. 3-A(+보강), 3-B 완료, 다음은 3-C-1(Debezium relay 교체)**

## 0. 새 세션(다른 PC·클라우드)에서 이어받을 때

이 저장소만 받으면 이어서 할 수 있게 정리해 둔 절이다. 이전 대화 기록은 넘어가지 않으므로 여기 적힌 것이 전부다.

**시작 순서**
1. 이 문서 → [AGENTS.md](../AGENTS.md)(코드·리뷰 규칙) → 할 작업에 걸린 ADR 순서로 읽는다
2. 다음 작업은 아래 4절 **3-C-1**. main에서 `feat/debezium-relay` 같은 브랜치를 만든다

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
- CI: `gh pr checks <번호>` → backend, saga(e2e), analyze(CodeQL), request-review 전부 pass. e2e는 4~5분
- 머지: `gh pr merge <번호> --squash --delete-branch`
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
| 후속 2 | 4단계 인증 (api-gateway, auth-service RS256 JWT + JWKS, Redis) | |
| 후속 3 | k8s (Helm, Strimzi, ArgoCD). 클러스터 상시 구동 대신 CI에서 kind로 검증 | 나중으로 미룸 |
| 후속 4 | 상품 검색 (product-service + 테이블 CDC + Elasticsearch) | 나중으로 미룸 |

후속 순서는 2026-11-22에 사용자와 합의했다. 브리프 5절은 아직 옛 순서(4~6단계 필수)라 3-E에서 고친다.

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
- 모든 발행은 outbox (비즈니스 변경 + outbox = 한 트랜잭션). relay는 지금 **스케줄러**(`libs/outbox`, `FOR UPDATE SKIP LOCKED`), 3-C에서 Debezium으로 교체
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
| (이번 PR) | 이 문서에 새 세션 이어받기 안내(0절), 3-C를 두 PR로 나눔 |

CI(`e2e`)가 매 PR마다 인프라 + 서비스를 실제로 띄워 시나리오 0·1·3·6·8과 IDOR 검사를 자동으로 돈다.

멱등 선점 원리(3-A 보강에서 테스트로 증명): 트랜잭션 첫 줄의 `insert ... on conflict (message_id) do nothing`이 선점 역할을 한다. 늦은 쪽은 앞 트랜잭션이 끝날 때까지 PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건(건너뜀), 롤백하면 1건(이어받아 처리). 테스트는 앞 트랜잭션을 커밋 직전에 멈춰 두고 늦은 쪽이 실제로 끝나지 않고 기다리는지까지 확인한다.

## 4. 남은 일 (3단계)

### 3-C. Debezium relay (다음 작업)

PR 두 개로 나눈다. 예상 3-C-1 3~4시간, 3-C-2 2~3시간(리뷰·CI 포함). 가장 불확실한 부분은 Debezium 설정(replication 권한, slot, 헤더 형식)을 로컬과 CI에서 맞추는 것.

- **3-C-1 relay 교체**: 아래 목록 중 Kafka 버전 맞추기 ~ e2e matrix까지. 끝나면 e2e가 scheduler/debezium 두 모드 모두 시나리오 0·1·3·6·8 통과
- **3-C-2 시나리오·ADR**: 시나리오 5·7, ADR 0004

세부:
- 시작할 때 Kafka 이미지 4.3.2 → 4.4.0(Dependabot PR #3, 브랜치 `dependabot/docker_compose/infra/apache/kafka-4.4.0`)을 compose·Testcontainers·Kafka Connect 버전과 함께 맞춘다. 3-C-1 PR에 포함하고 Dependabot PR #3은 그 뒤 닫는다
- compose에 Kafka Connect + Debezium Postgres 커넥터 추가 (Postgres는 이미 `wal_level=logical`)
- 서비스별 outbox 커넥터 JSON(`infra/connect/`) + 등록 스크립트(`scripts/register-connectors.sh`)
  - Outbox Event Router: `route.by.field=aggregatetype`(토픽), `aggregateid`가 key, `id`·`type`을 헤더로, `table.expand.json.payload=true`
  - 서비스별 DB 계정에 replication 권한, 커넥터마다 slot·publication 이름 분리
- 서비스 설정 `outbox.relay.enabled=false`로 스케줄러 끄기 (프로필 또는 환경 변수)
- 컨슈머 코드는 바꾸지 않는다 (헤더 이름을 맞춰 둠). Debezium이 헤더 값을 JSON 문자열로 감싸 보내는지 실제로 확인하고, 그렇다면 `Messages.messageId`에서 따옴표 처리
- e2e를 Debezium 모드로도 돌리기 (matrix: scheduler / debezium)
- 시나리오 5: 커넥터 중단 → 재시작 후 유실 0 + 중단 중 `pg_replication_slots` WAL 적체 관찰, heartbeat
- 시나리오 7: 스케줄러 vs Debezium, Saga 완료 지연·DB 부하 비교 (k6). 측정이 어려우면 ADR에 글로
- ADR 0004 (relay 교체, 측정 근거)

### 3-D. 타임아웃·장애 복구
- **멈춘 Saga 정리**: 같은 단계에 N분 넘게 있는 Saga를 찾아 커맨드 재전송(새 메시지 id, 참여 서비스는 주문별 기록으로 처음 결과 재응답) → 그래도 안 되면 역순 보상. 지금은 커맨드가 DLT로 가거나 응답이 안 오면 Saga가 그 단계에서 **계속 기다리는 구멍**이 있다
- **결제 타임아웃 보정**: 결과가 애매하면 바로 보상하지 않고 PG 조회(가짜 PG에 조회 API 추가) 후 결정
- **늦은 승인 자동 환불**: 타임아웃으로 이미 보상·취소했는데 PG 조회 결과가 승인이면 자동 환불 (drive 세션 제안 3)
- 서비스 다운 중 주문 → 복구 후 Saga 이어서 진행 (시나리오 4)
- 시나리오 2·4, ADR 0007

### 3-E. 정리 후 `v0.3-reliability` 태그
- 브리프 5절을 합의된 순서로 수정 (v0.3 = 완성, 후속 순서는 위 표)
- ADR 0000 "왜 MSA인가": 이 규모는 모놀리스로 충분하지만 분산 정합성을 검증하려고 나눴다 (drive 세션 제안 2)
- 시나리오 3·6 문서의 "CI 결과"를 PR #12·#13의 e2e 로그로 채우기 (#12: actions run `45033520460`)
- README 아키텍처를 3단계 기준으로, 시나리오 표·ADR 목록 정리

## 5. 알려진 한계·나중 일

- 멈춘 Saga 자동 정리 없음 → 3-D
- `processed_message` 보존 기간 정리 작업 없음 (ADR 0006, 토픽 보존 + DLT 재처리 기간보다 길게)
- 스케줄러 relay를 여러 인스턴스로 띄우면 같은 키의 순서가 바뀔 수 있음 → Debezium 교체로 해소 (ADR 0005)
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

- 실행 jar(`java -jar`)는 JDK 25가 필요하다. PATH의 java가 더 낮으면 JDK 25 경로로 직접 실행하거나 `JAVA_HOME`을 지정한다 (`bootRun`은 Gradle 툴체인이 알아서 맞춘다)
- Windows Git Bash에서 `docker compose exec … /opt/kafka/…`는 경로가 윈도 경로로 바뀌므로 `MSYS_NO_PATHCONV=1` (스크립트에는 이미 들어 있다)
- 로컬 Kafka·DB에 이전 단계 데이터가 남아 있으면 시나리오 3이 "응답 재발행"으로 실패할 수 있다(ADR 0006 배포 경계). 깨끗이 다시 하려면 `docker compose -f infra/docker-compose.yml down -v` (로컬 데이터 삭제)
