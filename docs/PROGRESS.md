# 진행 현황과 다음 작업

이어서 작업할 때 이 문서부터 본다. 설계 배경은 [브리프](commerce-msa-portfolio-brief.md), 결정 이유는 [ADR](adr/), 시나리오별 재현·결과는 [scenarios](scenarios/), 작업·리뷰 규칙은 [AGENTS.md](../AGENTS.md).

- 마지막 갱신: 2026-11-23 (main = `185524c`, PR #13 머지 직후)
- 현재 위치: **3단계(신뢰성) 진행 중. 3-A, 3-B 완료, 다음은 3-A 보강(동시성 테스트) → 3-C(Debezium relay)**

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

CI(`e2e`)가 매 PR마다 인프라 + 서비스를 실제로 띄워 시나리오 0·1·3·6·8과 IDOR 검사를 자동으로 돈다.

## 4. 남은 일 (3단계)

### 3-A 보강: 같은 메시지 동시 처리 테스트 (작은 PR, 3-C 전에)
- 지금 테스트는 "같은 메시지가 **순서대로** 두 번 오면 건너뜀"과 "롤백되면 기록도 사라짐"만 증명한다
- 추가할 것: 두 스레드가 **같은 messageId**로 동시에 `reserve`(inventory)·`use`(point)를 호출해도 재고·포인트가 한 번만 바뀌고 응답 outbox도 한 건인지 (Testcontainers Postgres, `CountDownLatch`로 동시 시작, `@RepeatedTest`)
- 원리: 트랜잭션 첫 줄의 `insert ... on conflict (message_id) do nothing`이 선점 역할. 늦은 쪽은 앞 트랜잭션이 끝날 때까지 PK 인덱스에서 기다렸다가, 앞이 커밋하면 0건(건너뜀), 롤백하면 1건(이어받아 처리)

### 3-C. Debezium relay
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
