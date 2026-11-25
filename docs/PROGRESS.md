# 진행 현황과 다음 작업

이어서 작업할 때 이 문서부터 본다. 설계 배경은 [브리프](commerce-msa-portfolio-brief.md), 결정 이유는 [ADR](adr/), 시나리오별 재현·결과는 [scenarios](scenarios/), 작업·리뷰 규칙은 [AGENTS.md](../AGENTS.md).

- 마지막 갱신: 2026-11-25 (후속 5 인증 재구성 설계 확정: 4-7절, 구현 전)
- 현재 위치: **3단계(신뢰성) 완료 = 완성 기준, 태그 `v0.3-reliability`. 후속 1(관측성) 완료(PR #27·#28). 후속 2(인증 + 프론트엔드): Boot 4.2(PR #29), A-1 auth-service(PR #30), A-2 Gateway·서비스 JWT 검증(PR #31) 완료, A-3-1 내 주문·결제 목록·주문 Idempotency-Key(PR #33), A-3-2 가입 → 포인트 계정(PR #34) 완료, F-1a Next.js 기본 구성·로그인·회원가입(ADR 0011, PR #35) 완료, F-1b 주문 화면(상품·주문·내 주문·Saga 타임라인, PR #36) 완료, F-2a 포인트 내역 API·포인트·결제 내역·알림 화면(PR #38), F-2b 로그인 기기 관리·관리자 화면·CI 브라우저 e2e(PR #39)로 **후속 2 완료, 태그 `v0.4-auth-frontend`**. **후속 3(k8s): K-1 이미지·Helm·Strimzi·kind CI(PR #40), K-2 Debezium(PR #41), K-3 ArgoCD·edge(PR #42)로 **후속 3 완료, 태그 `v0.5-k8s`**. 후속 1 보강 O-3 로그·수집기(Alloy·Loki, PR #45·#46). DDD 보강(값 객체 PR #56~#59, ADR 0015 PR #61) 완료. 다음은 0절 2번 순서의 ① 후속 4-1(4-1a product-service(PR #65) → 4-1b 서버 가격(PR #68) → 4-1c 장바구니(PR #72), 설계는 4-5절·ADR 0016) → ② 멱등 기록 정리(PR #74) → ③ 결제 서킷 브레이커(PR #76) 완료. ④ 후속 4 나머지(4-6절·ADR 0010) 완료: 4-2a 상품 테이블 CDC(PR #82) → 4-2b search-service(PR #89) → 4-2c 검색 화면(PR #90) → 4-3a 관리 이력 저장·API(PR #91) → 4-3b 관리자 상품 화면(PR #94)으로 **후속 4 완료, 태그 `v0.6-search-audit`**. 다음 작업은 **후속 5 인증 재구성**(SAS IdP + Gateway BFF + SPA 3개, 설계 확정 2026-11-25, 4-7절·[인증 재구성 설계](auth-sas-bff-design.md)). 5-0 ADR 0017 부터**. 후속 2 안의 선택은 추천안대로 진행(사용자 위임)**

## 0. 새 세션(다른 PC·클라우드)에서 이어받을 때

이 저장소만 받으면 이어서 할 수 있게 정리해 둔 절이다. 이전 대화 기록은 넘어가지 않으므로 여기 적힌 것이 전부다.

**시작 순서**
1. 이 문서 → [AGENTS.md](../AGENTS.md)(코드·리뷰 규칙) → 할 작업에 걸린 ADR 순서로 읽는다
2. **후속 2(태그 `v0.4-auth-frontend`)·후속 3 k8s(태그 `v0.5-k8s`)·후속 4 상품 검색·관리 이력(태그 `v0.6-search-audit`)까지 끝났다. 후속 4 다음 작업은 후속 5 인증 재구성이다(사용자 결정 2026-11-25, 4-7절·[인증 재구성 설계](auth-sas-bff-design.md)). 바꾸지 않을 것(서비스 JWT 검증, Kafka·Saga)과 PR 순서가 거기 있다. 아래는 후속 4 까지의 결정 기록이다: 다음 단계는 사용자가 정했다(2026-11-25): DDD 후보 1 값 객체(서비스마다 PR: order → inventory → point → payment) → 후보 2 컨텍스트 맵·용어집 ADR(도메인 이벤트·이벤트 소싱을 쓰지 않는 이유 포함) → 후속 4(상품·가격 스냅샷·ES). 값 객체(PR #56~#59)·ADR 0015(PR #61)까지 끝났다. 이후 순서도 사용자가 정했다(2026-11-25): ① 후속 4-1 가격 스냅샷(서버가 가격을 정함) → ② 멱등 기록 정리 작업 → ③ 결제 서킷 브레이커 → ④ 후속 4 나머지(검색·CDC·ES). 주문 취소 API 는 그 뒤에 정한다. 항목별 내용은 4-4절 "다음 작업 메모". 후속 4(4-1 포함)는 시작 전에 설계 메모(PR 나누기, 가격 스냅샷, CDC·ES 구성)를 이 문서에 적고 사용자 확인을 받는다. 4-1 설계는 사용자가 확인했다(2026-11-25, 4-5절·ADR 0016: 가격은 동기 조회 + 409, 장바구니 추가). 4-1 뒤 ②·③ 은 4-4절 메모대로 진행하고 머지 조건(Codex 지적 없음 + CI 통과)을 지키면 이어서 진행해도 된다(사용자 위임 2026-11-25). JPA Auditing·등록자/수정자 컬럼은 쓰지 않고, 관리자가 상품·가격을 고치는 기능이 생기는 후속 4 에서 도메인 이력으로 다시 본다(사용자 결정 2026-11-25, ADR 0015).** k8s 는 [`deploy/README.md`](../deploy/README.md) 와 [ADR 0014](adr/0014-k8s-deployment.md) 를 먼저 읽는다. 프론트엔드를 고칠 때는 `frontend/README.md` 와 ADR 0011. **후속 2·후속 3 안의 설계 선택(PR 나누기, API·차트 모양, 화면 구성, CI 범위 등)은 사용자가 "추천안대로 알아서" 하라고 했다(2026-11-23 후속 2, 2026-11-24 후속 3).** 추천안을 골라 진행한 뒤 PR 본문·이 문서에 이유를 적는다. 단 브리프에 없는 새 도구·인프라 도입, 단계 순서 변경은 여전히 묻는다. DDD 는 [ADR 0015](adr/0015-context-map-and-glossary.md)(컨텍스트 맵·용어집·쓰지 않기로 한 패턴)를 먼저 읽는다. 인증 흐름은 [ADR 0008](adr/0008-auth-self-issued-jwt.md)

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
- CI: `gh pr checks <번호>` 에 뜬 체크가 전부 pass. 워크플로는 바뀐 경로에 맞는 것만 뜨고(docs·md 만 바꾸면 CI 없음), 어떤 체크가 언제 뜨는지와 머지 후 배포(`release`)는 [ci-cd.md](ci-cd.md). e2e는 모드마다 6~7분(병렬)
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
| 후속 1 | 관측성 (분산 트레이싱으로 Saga 한 건을 서비스 넘어 추적, lag·DLT 경보, 로그) | 완료 (O-1 트레이싱 PR #27, O-2 메트릭·경보 PR #28). O-3 로그·수집기(Alloy·Loki)는 후속 3 뒤 추가(PR #45·#46) |
| 후속 2 | 인증 + 프론트엔드 (auth-service 자체 JWT, Spring Cloud Gateway, Next.js 화면 전체). 설계는 아래 "후속 2 설계 메모" | 완료, 태그 `v0.4-auth-frontend` (Boot 4.2 PR #29, A-1 PR #30, A-2 PR #31, A-3 PR #33·#34, F-1a PR #35, F-1b PR #36, F-2a PR #38, F-2b PR #39) |
| 후속 3 | k8s (Helm, Strimzi, ArgoCD). 클러스터 상시 구동 대신 CI에서 kind로 검증. 설계는 ADR 0014 | 완료 (K-1 PR #40, K-2 PR #41, K-3 PR #42, 태그 `v0.5-k8s`) |
| DDD 보강 | 값 객체(서비스마다), 컨텍스트 맵·용어집. 설계는 ADR 0015 | 완료 (값 객체 PR #56~#59, ADR 0015 PR #61) |
| 후속 4 | 상품 검색 (product-service + 가격 스냅샷 + 테이블 CDC + Elasticsearch). 상품 관리 기능의 변경 이력(audit)도 여기서 | 완료, 태그 `v0.6-search-audit` (4-1 PR #65·#68·#72, 4-2 PR #82·#89·#90, 4-3 PR #91·#94, 설계 4-5·4-6절, ADR 0010·0016) |
| 운영 보강 | 멱등 기록(`processed_message`·`Idempotency-Key`) 정리 작업, 결제 서킷 브레이커 | 완료 (PR #74·#76, 4-4절) |
| 후속 5 | 인증 재구성: auth-service → SAS(OIDC IdP), Gateway → BFF(토큰 릴레이), 화면 → SPA 3개(사용자·관리자·인증). 설계는 4-7절·[인증 재구성 설계](auth-sas-bff-design.md) | 설계 확정(2026-11-25), 구현 전 |

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

- 서비스마다 자기 DB·계정 (Postgres 하나에 DB 분리). 서비스 간 상태 변경은 Kafka 메시지만. 동기 HTTP 는 주문 생성 때 order → product 가격 조회 하나(ADR 0016, Saga 와 무관)
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
| #106 | 후속 5-0 [ADR 0017](adr/0017-auth-sas-gateway-bff-spa.md): auth-service → SAS(OIDC IdP), Gateway → BFF, 화면 → Vite SPA 3개, 고정 클라이언트 등록·시크릿 출처, 두 배치 방식. 고려한 안(자체 SSO·SAS·Keycloak / PKCE SPA·SCG BFF·Next BFF / 화면 2·3개 / Next CSR·Vite / DCR)과 대체 범위 표. ADR 0008(일부)·0011(전체)은 상태 줄만 대체 표시 |
| #100 | 4-4절 재고 락 측정(시나리오 18): `scripts/measure-hot-stock.sh` 가 `ReserveStock` 3000건을 `inventory.command` 에 직접 넣고(재고 단계만) 처리량(예약 기록 서버 시각)·리스너 한 건 시간(prometheus)·락 대기(`pg_stat_activity` 0.05초 샘플)를 잰다. 인기 상품 하나 약 150건/s, 인스턴스 3개여도 그대로, 상품이 나뉘면 258건/s → 비관적 락 유지 |
| (이번 PR) | release 가 main CI 를 기다림: 첫 잡 `ci`(`scripts/release-ci-gate.sh`)가 마지막 배포 이후 main 커밋 하나하나의 CI 가 모두 통과해야 넘어감(경로 필터·모듈별 빌드라 뒤 커밋 통과가 앞 실패를 대신하지 않음, 고친 뒤 수동 실행 `ci_from`), 실행이 안 보이는 커밋은 codeql 이 보일 때까지 대기, 실패·취소면 배포 중단. main CI 는 취소하지 않음(concurrency 그룹 = PR 은 브랜치, 그 밖은 실행 id). ADR 0014 "배포 전 main CI 대기" |
| #96 | release 가 바뀐 이미지만 배포(사용자 결정 2026-11-25): `scripts/release-plan.sh` 가 마지막 배포 커밋(`releasedCommit`)과 비교해 바뀐 이미지만 빌드(태그 = 이번 커밋, 나머지는 이전 태그), `release/eks` 에 release 커밋(main 커밋 + `release-tags.yaml`, 부모 = 이전 release·main), 차트 `image.tags.<이름>`, apps 차트가 `release-tags.yaml` 을 `.Files` 로 읽음(이미 등록된 root 변경 없음), rename 은 원래 경로도 판정(`--no-renames`), CI `k8s` 렌더링 확인, ADR 0014 "바뀐 서비스만 배포". |
| #95 | 문서: 후속 4 완료 정리(PROGRESS·README), 머지 커밋에 태그 `v0.6-search-audit` |
| #93 | 문서: 배포 환경 가이드 `docs/deployment-environments.md`(로컬·compose·k8s·ArgoCD·EKS, Helm·values·Application 매핑) |
| #94 | 후속 4-3b 관리자 상품 화면: `/admin/products`(판매 중지 포함 목록·페이지, 상품 등록) → `/admin/products/{id}`(이름·설명·가격·판매 상태 변경, 관리 이력 최근부터: 변경 종류·시각·관리자 id·바뀐 값만 전 → 후, 옮겨 온 옛 가격 이력은 가격만). 서버 액션이 저장 뒤 `refresh()` 로 다시 그림, 입력은 값이 바뀌면 key 로 다시 마운트. 관리자가 아니면 404. 입력 검사·이력 표시 `lib/admin/product-input.ts`(Vitest), Playwright `e2e/admin-products.spec.ts`(등록 → 가격·설명·판매 중지 → 이력 4건 순서·전후 값, 같은 가격 요청은 이력 없음, 일반 사용자 404) |
| #91 | 후속 4-3a 상품 관리 이력(ADR 0010 결정 12): `product_audit_log`(V3, 바꾼 전·후 값을 열로, JSON 없음) 에 등록·정보 수정·가격·판매 상태를 바꾼 관리자(JWT `sub`)·시각과 함께 상품 변경과 같은 트랜잭션으로 남김. 같은 값 요청은 남기지 않음(정보 수정도 값이 같으면 `updated_at` 을 바꾸지 않아 CDC 이벤트도 없음). 기존 가격 이력은 가격만 옮김. 조회 `GET /admin/products/{id}/audit-log`(ADMIN, 최근부터, 페이지). 등록·정보 수정·판매 상태 커맨드에도 관리자 id. 가격 이력 API 는 그대로 |
| #90 | 후속 4-2c 검색 화면: 홈 상품 목록 위에 검색 폼(검색어·최소/최대 가격·정렬, 자바스크립트 없는 GET 폼이라 조건이 URL 에 남음). 조건이 있으면 search-service(`/api/search/products`, 판매 중만), 없으면 지금처럼 product-service 목록. 검색 저장소 503 이면 전체 목록으로 대신 보여 주고 안내, 400 이면 메시지. "검색 결과 N개", 결과에서 바로 담기·주문(가격은 주문 때 다시 확인). URL 값은 `lib/search.ts` 가 걸러냄(Vitest). Playwright `e2e/search.spec.ts`(부분 검색·URL·전체 보기, 가격 범위·정렬), 브라우저 e2e 에 product-cdc 커넥터 등록 |
| #89 | 후속 4-2b search-service(:8089, ADR 0010): `product.cdc.products` 소비 → Elasticsearch 9.5.3 색인. 문서 id = 상품 id, **외부 버전 = 헤더 `__lsn`**(`version_type=external`) → 같은 변경 재전달·늦게 온 옛 변경은 ES 가 409 → 건너뜀(processed_message 없음). tombstone 은 삭제. 본문·키·헤더가 계약과 다르면·문서 거절(400)이면 DLT, ES 장애면 제자리 재시도. 색인 = 별칭 `products` → `products-v1`(없으면 처음에 만듦), ngram(1~2) 분석기로 한글 부분 검색, 매핑 strict. 공개 검색 API `GET /search/products`(q·minPrice·maxPrice·onSaleOnly·sort relevance/price-asc/price-desc/newest·page·size ≤100, 앞 10,000건), ES 장애면 503(상품 목록·주문은 영향 없음). REST 직접 호출(RestClient, 공식 클라이언트 안 씀 → OpenSearch 로 바꿀 여지). 지표 `search_indexing_lag_seconds`·`search_indexed_total{result}`. Gateway `/api/search/**`(GET, 공개). compose·Helm(StatefulSet + PVC, `node.store.allow_mmap=false`, ECK 안 씀)·CI(e2e·browser-e2e·k8s·release 이미지 11개)에 ES·search-service. 시나리오 17(`smoke-search.sh`: 부분 검색, 가격 변경 반영 시간, 판매 중지, 옛 변경 무시, ES 중단 중 503 → 재개 후 따라잡음(compose)). 테스트: 도메인·리스너·컨트롤러 단위, 실제 ES(버전·한글·필터·정렬), Kafka → ES 통합 |
| #82 | 후속 4-2a 상품 테이블 CDC: Debezium `product-cdc` 커넥터(compose `infra/connect/product-cdc.json`, Helm `KafkaConnector`, `values.services.product-service.cdc`)가 `products` 의 열 6개만(`column.include.list`) 변경 후 행(`ExtractNewRecordState`)으로 `product.cdc.products`(키 = 상품 id `ExtractField$Key`, 헤더 `__lsn`·`__source_ts_ms`, RegexRouter)에 보낸다. `product_svc` replication 권한(초기화 SQL 두 사본), 토픽·DLT·heartbeat 토픽(compose kafka-init, Helm topics). relay 가 스케줄러 모드여도 등록(e2e scheduler 모드에 등록 단계). 토픽 계약 문서(열은 추가만), AGENTS.md 토픽·키 규칙에 CDC 추가. 시나리오 16(`smoke-product-cdc.sh`, CI e2e 두 모드·k8s kind, k8s 활성 slot 6). ES 는 search-service 와 함께 4-2b 로 옮김(쓰는 쪽과 같이 들어가야 확인할 수 있음) |
| #81 | 문서: 후속 4 나머지 설계 메모(4-6절, 사용자 확인)·[ADR 0010](adr/0010-product-search-cqrs-table-cdc.md) 상품 검색 CQRS(테이블 CDC → search-service → ES). README ADR 목록 중복 줄 정리 |
| #76 | 4-4절 ③ 결제 서킷 브레이커: payment-service `CircuitBreakingPaymentGateway`(`@Primary`, Resilience4j 코어 2.3.0) 가 PG 승인만 감싼다. 최근 20건 중 10건 이상·실패(타임아웃·오류) 50% 이상이면 열림 → PG 를 부르지 않고 `PaymentGatewayUnavailableException` → 결제 서비스가 확정 실패(DECLINED, "PG unavailable (circuit open)")로 바로 응답 → Saga 즉시 보상. 30초 뒤 반열림 시험 3건(타임아웃이면 지금처럼 UNKNOWN + 보정). 처리마다 PG 를 부르기 전 `pg_approve_attempts`(V7)에 호출을 예약(calls + 1, 커밋)한다. 회로가 이번 호출을 막았을 때 예약이 이 처리 하나뿐이면 봉인(이후 어떤 처리도 PG 를 부르지 않음)하고 거절 확정, 아니면(재전달·Saga 재전송·다른 인스턴스 동시 처리) 거절 대신 UNKNOWN → 조회로 확정. 예약과 봉인은 같은 행의 조건부 갱신이라 겹치지 않음. 거절은 실패로 세지 않음. 조회·환불은 회로 밖(보정이 계속 돌아야 돈이 맞음). 지표 `payment_pg_circuit_state`. 라이브러리: Resilience4j 는 Spring Cloud CircuitBreaker 기본 구현이고 상태 머신(카운트 창·반열림 시험 수)이 검증돼 있어 직접 만들지 않음, Spring 통합 없이 코어만(설정은 우리 properties). 시나리오 15(`smoke-pg-circuit.sh`, CI e2e 마지막) |
| #74 | 4-4절 ② 멱등 기록 정리: order·inventory·point·payment 에 `IdempotencyCleanupScheduler`(1시간마다, `idempotency.cleanup.*`) → `IdempotencyCleanupService` 가 보존 기간(processed_message 30일 = 토픽 보존 7일 + DLT 재처리 여유, order Idempotency-Key 7일)이 지난 행을 오래된 것부터 1,000건씩(한 번에 최대 100배치) 지운다. `for update skip locked` 라 여러 인스턴스가 겹쳐도 서로 기다리지 않고 결과가 같다. 비즈니스 기록은 지우지 않음. order V8 `order_idempotency_keys(created_at)` 인덱스. 서비스마다 단위·Postgres 통합 테스트. 함께: 머지된 PR 번호(#49·#66·#67·#70·#71)·4-5절 완료 표시·5절 경보 전달 한계 반영(닫은 #69 내용) |
| #72 | 후속 4-1c 장바구니: order-service `cart_items`(V7, (user_id, product_id) PK, 담을 때 가격), `Cart` 애그리거트(담을 때 가격 유지, 50줄), `GET/PUT/DELETE /cart(/items/{id})`(Gateway `/api/cart/**`), 담을 때 지금 가격을 받아 남김(없는·판매 중지 422, 장애 503), 조회는 지금 가격과 비교해 `priceChanged`·장애면 담을 때 가격. 장바구니 주문 `POST /orders` + `fromCart` 는 주문 트랜잭션 안에서 주문한 줄 삭제. 화면 `/cart`(수량 변경·빼기·가격 변경 표시, 주문 폼 재사용), 상품 목록 "담기", 헤더 링크, Playwright 1개, 시나리오 14e |
| #68 | 후속 4-1b 서버 가격: order-service `PlaceOrderService`(트랜잭션 없음)가 같은 키 재요청이면 처음 주문 → 줄 검사 → `ProductCatalogPort`(RestClient, 연결·응답 2초, 재시도 없음)로 지금 가격 → 없는·판매 중지 422, 화면 가격과 다르면 409 + `currentPrices`, 같으면 `OrderService.placeOrder`(한 트랜잭션). product 장애 503(원인은 로그만). 409·422·503 에는 Idempotency-Key 를 남기지 않음. 프론트: 상품 목록·주문 폼을 `/api/products` 로, 보인 가격을 hidden 필드로 보내고 409 면 `옛 가격 → 새 가격` 안내 후 다시 누르면 새 가격. 주문 목록·상세의 상품 이름은 `?ids=` 로. 스모크 스크립트 가격을 시드 가격으로, 시나리오 14(`smoke-price-change.sh`, CI e2e). Helm order-service `PRODUCT_SERVICE_URL` |
| #65 | 후속 4-1a product-service(:8087, `product_db`): 상품 등록·수정·가격 변경·판매 중지(ADMIN, `/admin/products/**`), 가격 변경 이력 `product_price_history`(이전·새 가격, 바꾼 관리자, 시각. 가격 변경과 한 트랜잭션, 행 잠금으로 동시 변경에도 이력이 이어짐), 공개 조회(목록·상세·`?ids=` 여러 건). Flyway 시드 = 프론트 고정 상품 1~5, 새 상품은 1000 부터. Gateway `/api/products/**`(GET, 토큰 없이)·`/api/admin/products/**`, DB 계정, compose 메트릭 대상, Helm, CI(e2e·browser-e2e·k8s·release) 에 추가. 주문·화면은 아직 그대로(4-1b) |
| #64 | 문서: 후속 4-1 설계(4-5절), [ADR 0016](adr/0016-order-price-sync-query.md) 주문 가격 동기 조회 예외·409·장바구니 위치. 4-4절의 "4-1 은 동기 조회하지 않는다" 문장을 바로잡음 |
| #63 | 문서: 다음 작업 순서(후속 4-1 가격 스냅샷 → 멱등 기록 정리 → 결제 서킷 브레이커 → 후속 4 나머지)와 결정 메모(4-4절), AGENTS.md 서비스 간 동기 호출 규칙을 "상태 변경은 메시지, 조회는 읽기 모델 우선, 외부 PG 는 동기 + 결과 모름"으로 범위 정리 |
| #44, #47, #48, #50~#55, #60, #62, #66, #67, #70, #71 | 배포·관측성 보강: GHCR push·EKS ArgoCD GitOps(#44), Debezium 플러그인 다운로드 재시도(#47), trace·로그 resource 속성(#48), K-1 trace 문서(#50), relay 모드 롤링 전환 문서(#51), Alloy tail sampling(#52), docs 만 바뀐 커밋 CodeQL 건너뛰기(#53), [CI/CD 문서](ci-cd.md)(#54), Tempo 메모리 한도(#55), Kafka Connect 힙 512Mi(#60), 메트릭 수집 Alloy 로 통합·컨테이너 메모리 경보(#62) |
| #61 | DDD 후보 2: [ADR 0015](adr/0015-context-map-and-glossary.md) 컨텍스트 맵(OHS·Published Language·Conformist·ACL, 스키마만 공유) + 용어집(포인트 환불/결제 환불, 주문 취소/결제 취소 구분) + 쓰지 않기로 한 것(도메인 이벤트 객체, 이벤트 소싱, JPA Auditing·등록자/수정자 컬럼, 값 객체 공유) |
| #57~#59 | DDD 후보 1 값 객체 ②~④: inventory `ProductId`·`Quantity`, point `Points`·`UserId`, payment `Money`(PG 포트 `approve(orderId, userId, Money)`). 커맨드 값은 값 객체로 바꾸기 전에 원시 값으로 검사해 실패 응답(바로 바꾸면 예외로 무한 재시도), 값 객체 도입 전에 저장된 잘못된 거절 기록은 복원 때 건너뜀(보상 무한 재시도 방지, 회귀 테스트). payment 의 userId 는 규칙이 없어 감싸지 않음 |
| #56 | DDD 후보 1 값 객체 ① order-service: `Money`·`Points`·`Quantity`·`UserId`·`ProductId`(record, 생성 때 검증). `Order`·`OrderLine`·`OrderSaga`·`SagaAction`·`SagaCommandPort` 가 사용. 1P = 1원 환산은 `Points.toMoney()` 한 곳. DB 컬럼·메시지 계약·port.in Command·조회 DTO 는 원시 타입 그대로, 어댑터·서비스 입구에서 변환 |
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
| #49 | release 멀티 아키텍처: 이미지 10개(서비스 8 + 화면 + Connect)를 amd64·arm64 네이티브 러너에서 빌드해 `<커밋>` 하나로 묶음(`manifest` 잡), 두 아키텍처 확인 뒤 `release/eks` 이동, ADR 0014 멀티 아키텍처 절 |
| #46 | 후속 1 보강 O-3 k8s: 차트 `templates/observability.yaml`(alloy·tempo·loki·prometheus·kafka-exporter·grafana, 설정은 `files/observability` = infra 사본, CI diff), 서비스 trace·로그 export 켬, port-forward 에 Grafana 3030·Tempo 3210·Loki 3110·Prometheus 9091, CI kind 에서 시나리오 11·Prometheus 대상 확인, ADR 0014 관측성 절 |
| #45 | 후속 1 보강 O-3 로그·수집기: 서비스 trace·로그 → OTLP → Alloy → Tempo·Loki, Logback OpenTelemetry appender(서비스마다 `OpenTelemetryLoggingConfig`), Grafana span ↔ 로그 링크, order-service Saga 진행 로그, 시나리오 11c, ADR 0013 로그 절. k8s 는 로그 export 끔 |
| #42 | 후속 3 K-3 (후속 3 마지막): ArgoCD app-of-apps(`deploy/argocd`, root 하나 → Strimzi·Envoy Gateway·commerce-saga, revision 하나로 같은 커밋), edge = Gateway API(`Gateway`·`HTTPRoute`, Envoy Gateway, 화면만 공개), `scripts/k8s-argocd.sh`, CI `k8s` 의 `argocd` 잡(PR 커밋 sync → Synced·Healthy, edge 로 화면, 시나리오 0·1), ADR 0014 K-3 |
| #41 | 후속 3 K-2: Connect 이미지(`deploy/docker/connect.Dockerfile`, Strimzi Kafka + Debezium Postgres), 차트 `debezium.enabled`(KafkaConnect 하나 + 서비스별 KafkaConnector 5개, heartbeat 토픽, 서비스 스케줄러 relay 끔), CI 에서 커넥터 Ready·outbox 비어 있음·활성 slot 5개 확인 |
| #40 | 후속 3 K-1: 공용 서비스 이미지(`deploy/docker/service.Dockerfile`), Helm 차트 `deploy/helm/commerce-saga`(Postgres·Redis StatefulSet, Strimzi Kafka 4.3.2·KafkaTopic, 서비스 7개, 화면), `scripts/k8s-deploy.sh`·`k8s-port-forward.sh`, CI `k8s`(kind 배포 + 시나리오 0·1·9·10) |
| #39 | 후속 2 F-2b (후속 2 마지막): 로그인 기기 관리 `/sessions`(다른 기기·모든 기기 로그아웃), 관리자 `/admin`(ADMIN 만, 결제 장애 주입, DLT 안내), 헤더 메뉴(관리자는 토큰 roles 로 표시만), CI `browser-e2e`(서비스 7개 + `next start` + Playwright 13개), README 후속 2 완료 |
| #38 | 후속 2 F-2a: point-service 포인트 내역 `GET /points/me/history`(주문 사용 + 지급 기록 `point_grants`, 커서 = UUIDv7 id 하나), 가입 축하 지급 기록, 화면 `/points`·`/payments`·`/notifications`, 시나리오 13d, Playwright 내역 시나리오 2개 |

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
- 인프라: `grafana/tempo:2.10.10`(단일 바이너리, 로컬 디스크, 컨테이너 내리면 사라짐) + `grafana/grafana:13.2.6`(익명 Admin, Tempo 데이터 소스 프로비저닝). 호스트 포트 Tempo API **3210**, OTLP **4328**, Grafana 3000(F-2b 뒤 3030) (이 PC에 다른 관측성 스택이 3200·4318을 쓰고 있어 비킴). Tempo 이미지는 셸·wget이 없어 healthcheck 없음 → CI는 `/ready`를 curl로 기다림. Tempo 3.x는 구조가 바뀌어(설정 미확인) 2.10을 씀
- 시나리오 11 (`scripts/smoke-tracing.sh`, CI 두 모드, 시나리오 2 다음): 요청에 traceparent를 넣고 정상 주문 = span 9개·서비스 5개 + 주문 id 검색으로 같은 trace, 결제 실패 = 보상까지 한 trace. 로컬 Debezium 모드 PASS
- 배포 순서: 서비스(마이그레이션) 먼저, 커넥터 설정 나중(`table.field.additional.missing=error`라 컬럼이 없으면 커넥터 실패). 로컬에서 커넥터 재등록 직후 첫 주문은 재시작 때문에 10초 넘게 걸려 타임아웃 재전송이 한 번 났다
- 한계: 단계 타임아웃 재전송·결제 보정 후 응답·traceparent 없는 예전 메시지는 새 trace로 시작(주문 id 검색으로는 모임). JDBC span 없음

**O-3 로그·수집기 (PR #45·#46, 2026-11-24 사용자 요청: "Alloy 쓰는 걸로, Loki 빠져 있는 거 다") 에서 한 것 (결정·확인 사항)**
- 수집기 `grafana/alloy:v1.21.3`: 서비스가 trace·로그를 OTLP 로 보내면 trace → Tempo, 로그 → Loki (`infra/alloy/config.alloy`). 호스트 OTLP **4328** 을 Tempo 에서 Alloy 로 옮김(서비스 기본값 그대로), Alloy UI **12346**. 메트릭은 Alloy 를 거치지 않고 Prometheus 가 그대로 긁는다(pull, 경보 규칙이 Prometheus 에 있음)
- 로그 저장소 `grafana/loki:3.7.9`(단일 바이너리, 로컬 디스크, tsdb·v13, structured metadata), 호스트 **3110**. Loki·Alloy 이미지도 healthcheck 도구가 없어 CI 가 `/ready`·`/-/ready` 를 curl 로 기다림
- 로그 보내는 법은 OTLP(파일·stdout 수집 아님): 로컬·CI 서비스가 호스트에서 `java -jar` 로 돌아서. Boot 4.2 는 OTLP 로그 exporter 까지만 만들고 appender 가 없어 `opentelemetry-logback-appender-1.0:2.33.1-alpha`(boot-app-conventions) + 서비스마다 `OpenTelemetryLoggingConfig`(기동 후 root logger 에 붙임, 컨텍스트 닫히면 뗌). 설정 `management.opentelemetry.logging.export.otlp.endpoint`(`OTLP_LOGGING_ENDPOINT`, 기본 `http://localhost:4328/v1/logs`), `management.logging.export.enabled`(`OTLP_LOGGING_EXPORT_ENABLED`, Helm 은 false)
- Grafana: Tempo `tracesToLogsV2`(span → `{service_name="…"} | trace_id="…"`), Loki `derivedFields`(로그의 `trace_id` → Tempo). 로컬에서 span "Related logs" → Loki 창 확인
- order-service 가 Saga 전이마다 `Saga 진행: <응답> -> <상태> (orderId=…)` INFO. 결제 거절은 payment-service 가 로그를 남기지 않아 11c 근거로 씀
- 시나리오 11c: 결제 실패 trace id 로 Loki 에서 order·notification 로그. 로컬 Debezium 모드 PASS
- k8s: 다음 PR(관측성 k8s)에서 차트에 같은 구성을 붙였다

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
| F-2a | 포인트 내역 API(point-service) + 포인트·결제 내역·알림 화면 | 완료 PR #38 |
| F-2b | 로그인 기기 관리 + 관리자(결제 장애 주입. DLT 는 화면 없이 안내, 준비 메모 #6) + 브라우저 e2e CI + 후속 2 마무리 | PR #39 (후속 2 끝) |

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
- 한계: 키는 7일 보존 뒤 정리 스케줄러가 지운다(4-4절 ②, 지난 키로 다시 보내면 새 주문). 처음 요청이 처리 중이면 같은 키 요청은 기다렸다가 결과를 받는다(409 즉시 반환 아님)

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
- 개발 서버는 3001(그때 3000 은 Grafana. F-2b 뒤 Grafana 는 3030, 화면은 3500). `.claude/launch.json` 은 로컬용이라 커밋하지 않았다
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

**F-2a 포인트·결제 내역·알림 (PR #38) 에서 한 것 (결정·확인 사항)**
- 포인트 내역 API `GET /points/me/history?before=&size=`(Gateway `/api/points/me/history`, 기존 라우트 그대로). 다른 목록과 같은 커서 모양(`size` 1~50, 기본 20, 최신순). 항목: `entryId`(커서), `kind`(`ORDER` 또는 `SIGNUP_BONUS`), `orderId`(주문 사용만), `amount`, `status`(`USED`·`REJECTED`·`REFUNDED` 또는 `GRANTED`), `reason`, 시각
- 가입 축하 지급을 어떻게 보일지(시작 메모의 숙제): **지급 기록 테이블 `point_grants`(V5)를 두고 계정을 만든 트랜잭션에서 함께 INSERT**(처리 기록 + 계정 + 지급 기록 = 한 트랜잭션, 계정이 이미 있거나 0P 면 남기지 않음). 대안이던 "잔액 응답에 가입 축하 금액만 싣기"는 지급 종류가 늘면 다시 바꿔야 해서 버림
- 두 기록을 한 목록으로: 주문 id(order-service)와 지급 id(point-service) 모두 UUIDv7 이라 id 하나로 정렬·커서. 각 표에서 최신 size 개씩 읽어 `union all` 후 다시 size 개(인덱스 `(user_id, order_id)`·`(user_id, id)`). 두 서비스 시계가 조금 달라도 커서가 같은 id 라 쪽 사이에 빠지거나 겹치는 항목은 없다(순서만 수 ms 어긋날 수 있음)
- 사용자를 모르는 기록(사용 전에 온 환불, `user_id` null)은 누구의 내역에도 안 나온다. 거절(`REJECTED`)은 "사용 안 됨", 보상 환불(`REFUNDED`)은 "환불됨"으로 잔액 변화 0 으로 보여 준다(한 주문 = 한 줄)
- 화면: `/points`(잔액 + 내역, 주문 사용은 주문 상세 링크), `/payments`(결제 내역, 상태 5가지 문구), `/notifications`(알림, 숫자 커서). 헤더에 포인트·결제·알림. 문구·커서 검사는 `src/lib/history/` 순수 함수(Vitest 63개, +8). 잘못된 `?before=` 는 첫 쪽으로
- 확인(로컬, Debezium 모드 서비스 7개 + 커넥터 5개 + `next dev`): point-service 테스트 51개(Testcontainers 포함), 시나리오 13(13d 추가) PASS, user1 로 세 화면·다음 쪽 링크, Playwright 9개(새 `e2e/history.spec.ts` 2개: 가입 축하 → 주문 사용 → 결제 완료 → 완료 알림, 포인트 부족 → "사용 안 됨"·결제 없음·취소 알림)
- 이 PC 환경 메모: Node 22.12 에서는 Vitest 가 undici 로드에 실패한다(engines `^24.15`). `npx -p node@24 node node_modules/vitest/vitest.mjs run` 처럼 Node 24 로 돌렸다. Playwright 브라우저(1255)가 없어 설치된 Edge 를 `launchOptions.executablePath` 로 지정한 임시 설정으로 돌렸다(커밋 안 함)
- 한계: F-2a 전에 가입해 계정이 이미 있던 사용자는 지급 기록이 없다(로컬·CI 데이터만). 시드 사용자 1~4 의 시작 잔액도 지급 기록이 아니다(시드)

**F-2b 로그인 기기·관리자·CI 브라우저 e2e (PR #39) 에서 한 것 (결정·확인 사항)**
- `/sessions`: 헤더의 "사용자 #N" 을 누르면 간다. auth-service `GET /auth/sessions` 에 Bearer + refresh 쿠키(이 기기 표시)를 Next 서버가 보낸다. 이 기기는 맨 위·"이 기기" 표시만(로그아웃은 헤더 버튼), 다른 기기는 "로그아웃"(`DELETE /auth/sessions/{id}`, 소유자 검사는 auth-service), "모든 기기에서 로그아웃"(`POST /auth/logout-all`) 뒤 이 브라우저 쿠키도 지우고 로그인 화면. Server Action 은 세션 id 를 UUID 형식으로 먼저 거르고, 이미 없는 세션(404)은 목록만 다시 그린다. 기기 이름은 User-Agent 에서 브라우저·OS 만(`lib/auth/device.ts`)
- 로그아웃한 다른 기기는 access token(최대 15분)이 끝나면 proxy 의 refresh 가 거절돼 로그인 화면으로 간다(즉시 차단 없음, ADR 0008 한계 그대로). Playwright 는 그 기기의 access 쿠키만 지워 확인한다
- `/admin`: 토큰 `roles` 에 ADMIN 이 없으면 404(메뉴도 안 보임). 표시용일 뿐 권한은 Gateway `/api/admin/**` = ADMIN 과 payment-service 가 서명된 토큰으로 검사한다(위조 쿠키로 화면을 열어도 API 403 → 404). 결제 장애 주입 폼(승인·거절·지연 ms, 0~60,000), DLT 는 준비 메모 #6 대로 화면 없이 Kafka UI(`:8989`)·`scripts/dlt.sh` 안내
- 폼은 제어 컴포넌트 + `onSubmit` 에서 Server Action 을 직접 부른다. 처음에는 저장 뒤 React 의 폼 초기화로 라디오가 옛 값으로 돌아가 보였다(Playwright 로 잡음). F-1b 주문 폼과 같은 방식
- **브라우저 e2e 를 CI 에 넣었다**(준비 메모 "F-1 CI" 판단): 새 워크플로 `browser-e2e`(frontend·서비스·infra 변경 때). bootJar + `next build` + Playwright chromium → 인프라 + 서비스 7개(스케줄러 relay) + `next start :3500` → `e2e/` 13개. e2e(saga) 워크플로에 붙이지 않은 이유: 시나리오 9 때문에 Gateway 사용자 rate limit 을 초당 5 로 낮춰 띄우는데, 화면은 요청 한 번에 API 를 여러 개 불러 429 가 섞인다. 실패하면 Playwright 결과를 artifact 로 올린다
- 확인(로컬, 서비스 7개 Debezium 모드 + `next dev`): Vitest 83개(+20: 토큰 roles, 기기 관리 호출, 기기 이름, 장애 입력, 두 Server Action), lint·typecheck·format, Playwright 13개(새 `sessions.spec` 2개: 다른 기기 로그아웃 → 그 기기 refresh 거절, 모든 기기 로그아웃 / `admin.spec` 2개: 거절 주입 → 결제 실패 → 포인트 환불·재고 해제·내역 "환불됨"·결제 "거절" → 정상 승인으로 되돌림, 일반 사용자는 메뉴 없음·404)
- 후속 2 완료: README 상태·구성 갱신. 태그 `v0.4-auth-frontend`

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
- F-2b (완료, 위 절): 로그인 기기 관리 + 관리자 결제 장애 주입 화면 + 브라우저 e2e 를 CI 에 넣을지 판단(준비 메모 "F-1 CI" 항목) + 후속 2 마무리(README. 태그를 붙일지는 사용자에게 묻는다)

**로컬 실행 (F-1b 때 쓴 방법)**
- 인프라: `docker compose -f infra/docker-compose.yml up -d --wait`
- 서비스: `./gradlew build` 후 `java -jar services/<이름>/build/libs/<이름>-0.0.1-SNAPSHOT.jar` (JDK 25. 이 PC는 `C:/Users/user/.jdks/openjdk-25.0.2`). 화면 전체에 필요한 것: auth 8086, point 8083, order 8081, inventory 8082, payment 8084, notification 8085, gateway 8000
- 화면: `cd frontend && npm run dev` (3500, `package.json` 에 고정. Grafana 는 3030). Playwright: `npm run test:e2e` (한 worker). Node 는 24.15 이상(22 에서는 Vitest 가 undici 로드 실패)
- 관리자 토큰: `admin` / `local-dev-pass` 로 `POST localhost:8000/auth/login`. 결제 장애 주입은 확인 뒤 `{"mode":"APPROVE","delayMillis":0}` 로 되돌린다
- Redis·Kafka 컨테이너를 다시 띄우면 오래 떠 있던 gateway 가 `/auth` 요청에 응답하지 않은 적이 있다(F-1a). 그러면 gateway 를 다시 띄운다

**DDD 점검 결과와 후속 후보 (2026-11-24 사용자 질문. 2026-11-25 후보 1·2 완료, 정리는 ADR 0015)**
- 이미 된 것: 서비스 = bounded context(DB 분리, 메시지로만 연결, 계약은 `messaging-contract`). 행위를 가진 애그리거트(`Order`·`OrderSaga`·`Stock`·`StockReservation`·`PointAccount`·`Payment`·`User`·`RefreshFamily`, setter 없음). 도메인 모델과 JPA 엔티티 분리(ADR 0002: 애그리거트는 순수 자바, `@Entity` 는 어댑터 안 package-private, ArchUnit 이 검사). 루트마다 리포지토리(포트), 다른 애그리거트는 ID 참조, 쓰기만 애그리거트·조회는 DTO
- 의도적 타협(바꾸지 않음): 재고 예약은 한 트랜잭션에서 여러 `Stock` + `StockReservation` 을 바꾼다(ADR 0002). 도메인 이벤트 객체 없이 애플리케이션 서비스가 outbox 포트에 쓴다. notification 은 단순 구조
- 후보 1 (완료, PR #56~#59): 값 객체. 원칙: 규칙이 있는 값만 감싼다, 도메인 안에서만 쓰고 DB 컬럼·메시지 계약·port.in Command·조회 DTO 는 원시 타입(스키마·계약 변경 없음), 서비스끼리 공유하지 않고 서비스마다 자기 타입(서비스 경계 규칙). 도메인 이벤트 객체는 도입하지 않는다(이벤트 종류가 적고 발행 지점이 한 곳, `SagaAction`·`boolean` 반환으로 도메인이 이미 결정) — 후보 2 ADR 에 이유를 적는다. 원래 메모: 금액·포인트·수량·userId 가 `long`/`int` 라 "0 이상"·"포인트와 원화 구분" 규칙이 흩어져 있다 → `Money`·`Points`·`Quantity` 등. 추천 시점은 F-2 다음 별도 PR
- 후보 2 (완료, ADR 0015): 컨텍스트 맵 + 용어집 ADR(서비스 관계: 오케스트레이터·참여자·순수 소비자·공개 이벤트. 용어: 예약·확정·해제, 차감·환불, 보상). F-2b 끝에 붙여도 된다

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

## 4-3. 후속 3. k8s (완료)

2026-11-24 사용자 합의: 아래 추천안대로 PR 셋, 세부 선택은 추천안대로. 결정은 [ADR 0014](adr/0014-k8s-deployment.md), 사용법은 [`deploy/README.md`](../deploy/README.md).

| 순서 | PR | 상태 |
|---|---|---|
| K-1 | 공용 서비스 이미지 + Helm 차트(Postgres·Redis StatefulSet, Strimzi Kafka·KafkaTopic, 서비스 7개, 화면) + CI kind 워크플로(시나리오 0·1·9·10) | 완료 PR #40 |
| K-2 | Strimzi `KafkaConnect`·`KafkaConnector` 로 Debezium relay(ADR 0004 의 배포 기준) + Debezium 발행 확인 | 완료 PR #41 |
| K-3 | ArgoCD(app-of-apps, CI kind 에서 GitHub 저장소를 sync 해 Healthy 확인) + edge(외부 공개는 이것 하나) + ADR 0014 보강 | PR #42 |

**K-1 (PR #40) 에서 한 것 (결정·확인 사항)**
- 이미지: `deploy/docker/service.Dockerfile` 하나로 7개. bootJar 를 받아 레이어로 풀어 비 root 로 실행. 이미지 안에서 Gradle 을 돌리지 않는다
- 차트 하나(`deploy/helm/commerce-saga`): 서비스는 `values.services` 의 한 항목(포트·DB·추가 env), 서비스마다 자기 DB 계정 Secret 만. Postgres·Redis 는 오퍼레이터 없이 StatefulSet·Deployment. Postgres 초기화 SQL 은 `infra/postgres/init` 사본(CI 가 diff)
- Kafka: Strimzi 1.3.1(2026-11-04) 이 지원하는 마지막 버전 **4.3.2**(KRaft 노드 하나). 클라이언트 4.4.0 과 호환. 토픽 16개(DLT 포함)는 `KafkaTopic`, 자동 생성 끔
- trace 없음(`TRACING_SAMPLING_PROBABILITY=0`, 클러스터에 Tempo 없음). auth-service replicas 1 + `Recreate`(임시 서명 키, 재배포 중 키가 다른 Pod 동시 응답 방지. Codex 지적)
- Codex 반영: (P2) 같은 태그 `dev` 로 다시 배포하면 Pod 가 안 바뀜 → `k8s-deploy.sh` 기본 태그를 빌드마다 새로. (P2) auth-service RollingUpdate 중 임시 키가 다른 Pod 둘 → `Recreate`
- CI `k8s`: kind(v0.33, 노드 1.37) → 이미지 빌드·`kind load` → Strimzi(Helm OCI) → 차트 → Ready 대기 → `port-forward` 로 compose 와 같은 포트 → 기존 스모크 그대로. 서드파티 액션은 커밋 해시로 고정(CodeQL 지적)
- 로컬에는 kind 가 없어(Docker Desktop 만) 렌더링·`helm lint`(alpine/helm 이미지)·order-service 이미지 실행까지 확인하고 배포는 CI 로 확인

**K-2 (PR #41) 에서 한 것 (결정·확인 사항)**
- Connect 이미지 `deploy/docker/connect.Dockerfile`: Strimzi Kafka 이미지(1.3.1, Kafka 4.3.2) + Debezium Postgres 플러그인(compose 와 같은 3.7.1.Final·SHA-512). Strimzi `build` 는 kind 에 레지스트리가 없어 쓰지 않음
- 차트 `debezium.enabled`(기본 true): `KafkaConnect` 하나(`strimzi.io/use-connector-resources`) + 서비스별 `KafkaConnector` 5개(compose `infra/connect/*.json` 과 같은 설정), heartbeat `KafkaTopic` 5개. outbox 가 있는 서비스는 `OUTBOX_RELAY_ENABLED=false`
- DB 비밀번호: 서비스 DB Secret → Connect 컨테이너 env → 커넥터 `${env:...}`(EnvVarConfigProvider). CR 에 비밀번호를 쓰지 않음
- CI `k8s`: 스모크 뒤 커넥터 5개 Ready, outbox 5개 비어 있음, 활성 replication slot 5개
- CI 첫 실행 실패: Strimzi v1 `KafkaConnect` 는 워커 그룹·내부 토픽을 config 가 아니라 spec 필드(`groupId`, `configStorageTopic` 등)로 받는다 → 고침
- Codex 반영 (P0): slot 이 생기기 전에 Debezium 모드로 쓰면 메시지를 잃는다(넣고 바로 지우므로). k8s 는 Pod 가 뜨자마자 Kafka 를 소비해 배포 순서로 막을 수 없다 → `OutboxWriter` 가 slot·publication 을 확인하고 없으면 `OutboxNotReadyException` 으로 롤백(리스너는 재시도, 유실 대신 지연). ADR 0014 K-2 추가 결정, `OutboxWriterIntegrationTest` 2개

**K-3 (PR #42) 에서 한 것 (결정·확인 사항)**
- ArgoCD app-of-apps: `deploy/argocd/root.yaml` 하나만 apply. 자식(Strimzi·Envoy Gateway = OCI Helm, commerce-saga = 이 저장소 차트)은 `deploy/argocd/apps` 작은 Helm 차트라 `revision` 값 하나로 같은 커밋을 본다. 플랫폼 sync-wave -2, CRD 가 없을 때는 `SkipDryRunOnMissingResource` + 재시도. OCI 저장소는 repository Secret(`enableOCI`)
- edge: Gateway API(`Gateway`·`HTTPRoute`) + Envoy Gateway(v1.10.0). ingress-nginx 은퇴(2026)로 Ingress 대신 표준의 다음 API. **화면(Next)만 공개**(ADR 0011 BFF), Spring Gateway·서비스·DB·Kafka·Redis 는 내부망. Envoy Service 타입은 값(kind = ClusterIP, LoadBalancer 면 주소가 없어 Programmed 안 됨)
- `scripts/k8s-argocd.sh`: 이미지 → kind(빌드마다 새 태그, root → 자식 Application 의 `image.tag` 로 전달. Codex P1: `dev` 고정이면 다시 돌려도 옛 이미지로 Synced·Healthy), ArgoCD(chart 10.18.0) 설치, root apply(PR 커밋으로 바꿔 넣음), 네 Application Synced·Healthy + 커넥터 Ready 대기
- CI `k8s` 의 `argocd` 잡: 위 스크립트 → edge 로 `/login` 200 → port-forward → 시나리오 0·1. 첫 실행에 통과
- 후속 3 완료. 태그 `v0.5-k8s`

## 4-4. 다음 작업 메모 (2026-11-25 사용자 결정)

정합성 점검(2026-11-25 사용자 질문)에서 정한 것. outbox 원자성, 멱등 두 겹, 행 락, 단계 타임아웃·역순 보상, 결제 조회 확정·늦은 승인 환불은 시나리오로 검증돼 있어 바꾸지 않는다. **실제 결함은 가격 하나**이고, 나머지는 운영·장애 대응 보강이다. 순서는 ①→④, 각각 PR 하나 이상.

| 순서 | 작업 | 내용 | 메모 |
|---|---|---|---|
| ① | 후속 4-1 가격 스냅샷 | 서버가 상품 가격을 정하고, 주문에 주문 시점 가격을 남긴다. 클라이언트가 보내는 `unitPrice`(5절 한계)를 믿지 않는다 | 시작 전 설계 메모 + 사용자 확인. 설계 확정(2026-11-25): 4-5절·ADR 0016. product-service 신설, 주문 때 동기 조회 + 화면 가격과 다르면 409, 장바구니 추가 |
| ② | 멱등 기록 정리 (완료, PR #74) | `processed_message`(메시지를 받는 서비스 전부)·`order_idempotency_keys` 의 보존 기간이 지난 행을 나눠서(예: 1,000건씩) 지우는 스케줄러. 기존 `SagaTimeoutScheduler`·`PaymentReconcileScheduler` 와 같은 모양 | 보존 기간: `processed_message` 는 Kafka 토픽 보존 + DLT 재처리 기간보다 길게(ADR 0006), `Idempotency-Key` 는 24시간~7일 중 PR 에서 정한다. 만료 뒤 같은 키는 새 주문이 되는 것을 문서에 남긴다. 비즈니스 기록(주문·Saga 기록·예약·사용·결제)은 지우지 않는다. 삭제는 여러 인스턴스에서 겹쳐도 결과가 같다 |
| ③ | 결제 서킷 브레이커 (완료, PR #76) | payment-service 의 PG 승인 호출에 서킷 브레이커. 타임아웃이 이어지면 회로를 열고, 열려 있는 동안은 **PG 를 부르지 않았으므로 확정 실패**(`PaymentFailed`)로 바로 응답 → Saga 가 곧바로 보상 | 반열림 상태의 시험 호출은 지금처럼 타임아웃이면 `UNKNOWN` + 보정(ADR 0007 그대로). 조회(`inquire`)·환불은 회로와 따로 둘지 PR 에서 정한다. 라이브러리(Resilience4j 등)는 PR 에서 고르고 이유를 적는다. 가짜 PG 장애 주입으로 시나리오 추가(PG 장애 중 주문 → 즉시 실패·보상 → 복구 후 정상) |
| ④ | 후속 4 나머지 (완료, PR #82~#94) | 상품 검색(테이블 CDC + Elasticsearch), 상품·가격 관리 이력 | 설계 확정(2026-11-25): 4-6절·ADR 0010 |

**정하고 미룬 것 (지금은 하지 않음)**
- 재고: 비관적 락(`SELECT … FOR UPDATE`, productId 순서)을 유지한다. 측정(시나리오 18, 2026-11-25): 인기 상품 하나는 약 150건/s 에서 막히고 인스턴스를 3개로 늘려도 그대로(락 대기 83~88%), 상품이 나뉘면 158 → 258건/s. 이 규모에는 충분해 바꾸지 않는다. 더 필요해지면 먼저 조건부 UPDATE 를 트랜잭션 끝으로 옮겨 락 쥐는 시간을 줄이고 같은 스크립트로 다시 잰다. 재고 버킷·Redis 선점은 하지 않음
- 주문 취소 API(내 주문·주문 상세 화면): Saga 진행 중 취소는 취소 요청을 기록하고 다음 응답에서 보상으로 돌린다. 완료된 주문 취소는 결제 환불 → 포인트 환불 → 재고 복구의 별도 취소 Saga. 순서는 ④ 뒤에 정한다
- PG 장애 대처 중 PG 이중화(다른 PG 로 우회)·결제 컨슈머 일시정지는 설명만 한다(가짜 PG 하나로는 보여 주기 어렵고, 결제는 빨리 실패시키는 쪽이 흔하다)
- 주문 진행 표시는 폴링(1초) 유지. SSE·웹소켓은 연결 관리 비용에 비해 이득이 작다

**AGENTS.md 서비스 간 동기 호출 규칙 (PR #63)**
- 금지 범위를 "서비스 간 모든 동기 HTTP"에서 "상태 변경"으로 좁히고, 조회는 이벤트로 받은 읽기 모델을 먼저 쓰며 동기 조회는 ADR 로 이유를 남긴 예외로, 외부 시스템 호출은 그 서비스 안에서 동기·트랜잭션 밖으로, 그중 상태나 돈을 바꾸는 호출(PG 결제·환불 등)은 타임아웃이면 "결과 모름"(ADR 0007)으로 적었다(알림 발송·외부 조회까지 조회 확정을 강제하지 않으려고 범위를 좁힘). ~~이 규칙 때문에 4-1 에서 order-service 는 가격을 동기 조회하지 않는다~~ → 다시 검토해(2026-11-25) 4-1 은 이 규칙의 "실시간 값이 꼭 필요할 때" 예외로 **동기 조회**한다. 이유는 ADR 0016(가격은 주문 순간 한 번, 실시간 값이 필요, 상태를 바꾸지 않음, Saga 와 무관). 실무에서 동기 호출이 흔하다는 것과 이 저장소가 장애 격리 때문에 상태 변경을 메시지로만 하는 것(ADR 0000)을 구분하려는 것. 지금 코드는 바뀌지 않는다. 머지된 ADR 0000 은 고치지 않는다

## 4-5. 후속 4-1 설계 메모 (2026-11-25 사용자 확인)

결정과 이유는 [ADR 0016](adr/0016-order-price-sync-query.md). 흔한 커머스 흐름(목록 가격은 안내용 → 주문 직전 서버가 다시 계산 → 다르면 알리고 재확인 → 주문에 스냅샷)을 따른다.

| PR | 범위 |
|---|---|
| 4-1a product-service (완료, PR #65) | 새 서비스(헥사고날, 자기 DB `product_db`). 상품 등록·수정·가격 변경(ADMIN), 가격 변경 이력 `product_price_history`(이전·새 가격, 바꾼 사람 = JWT `sub`, 시각. 가격 변경과 같은 트랜잭션), 판매 중지. 공개 조회: 목록(페이지)·상세·여러 건(`GET /products?ids=`). Flyway 시드 = 지금 프론트 고정 상품 1~5(재고 시드와 같은 id). Gateway `/api/products/**`, compose·Helm·CI 에 서비스 추가. 메시지는 주고받지 않는다(Kafka·outbox 없음) |
| 4-1b 서버 가격 (완료, PR #68) | order-service `ProductPricePort`(adapter.out, RestClient, 타임아웃 2초, 재시도 없음, 트랜잭션 밖). 화면 가격(`unitPrice`)과 다르면 409 + 줄별 현재 가격, 같으면 서버 가격 스냅샷. 없는·판매 중지 상품 422, product 장애 503. 이때는 `Idempotency-Key` 를 기록하지 않음. 프론트 상품 목록을 API 로, 409 확인 창. 시나리오 문서(가격 변경 뒤 주문) |
| 4-1c 장바구니 (완료, PR #72) | order-service `cart_items`(user_id, product_id 유니크, 수량, 담을 때 가격). 담기·수량 변경·빼기·조회(현재 가격과 비교해 바뀐 줄 표시, product 장애면 표시 없이 담을 때 가격)·장바구니 주문(주문 생성 + 주문한 줄 삭제 = 한 트랜잭션, 409 는 4-1b 와 같음). 내 장바구니만(JWT `sub`), 최대 50줄. 프론트 장바구니 화면 |

정해서 진행한 것: 타임아웃 2초, 가격이 오르든 내리든 409, 장바구니 50줄, 상품 조회는 로그인 없이 공개(order 는 Gateway 를 거치지 않고 클러스터 안에서 호출, 토큰 전달 없음), 메시지 계약은 바꾸지 않음, `product.events` 는 만들지 않음(소비자가 생기면 추가).
k8s: product-service 를 Helm 차트에 추가하고 기존 kind CI 로 같이 확인한다. Elasticsearch 는 ④에서 따로.

## 4-6. 후속 4 나머지 설계 메모: 상품 검색(테이블 CDC + Elasticsearch)·상품 관리 이력 (2026-11-25 사용자 확인: 추천안대로)

브리프 "상품 검색" 절의 구성(쓰기 Postgres, 검색 ES = CQRS)을 따르고, Debezium 을 outbox 릴레이가 아닌 **테이블 CDC(데이터 복제)** 로도 써서 두 쓰임새를 보여 준다. 결정은 ADR 0010(브리프에서 번호를 비워 둠)으로 남긴다.

```
product-service ─ products 테이블 ─ Debezium(테이블 CDC) ─▶ product.cdc.products ─▶ search-service ─▶ Elasticsearch
                                                                                         ▲
                                         화면 검색창 ─▶ Gateway /api/search/** ──────────┘
```

**정할 것과 추천**

| # | 정할 것 | 추천 | 이유·대안 |
|---|---|---|---|
| 1 | 검색 서비스 모양 | `search-service` 하나가 ES 를 소유: CDC 소비(색인) + 검색 API | 서비스가 자기 저장소를 가진다는 규칙과 맞다. 브리프의 `search-indexer`(색인만) + 검색 API 분리는 서비스가 하나 더 늘고 ES 를 둘이 같이 쓰게 된다 |
| 2 | CDC 를 쓸지, `product.events`(outbox)로 할지 | **테이블 CDC** (브리프 의도). 대신 테이블이 다른 서비스에 새는 것을 줄인다: 커넥터 `column.include.list` 로 검색에 필요한 열만, `ExtractNewRecordState` 로 변경 후 행만 보내고, 이 토픽 모양을 product-service 의 계약으로 문서화(열은 추가만) | 테이블 CDC 는 내부 스키마가 곧 계약이 되는 약점이 있다(ADR 에 적음). outbox 이벤트가 더 깔끔하지만 이미 3단계에서 보였고, "Debezium 의 두 쓰임새"가 이 단계의 목적 |
| 3 | 토픽·키 | `product.cdc.products`, 키 = 상품 id | AGENTS.md 키 규칙(orderId, auth 는 userId)에 상품 id 를 예외로 추가. 상품 단위 순서만 필요 |
| 4 | 중복·순서 | ES 문서 id = 상품 id, **외부 버전 = 원본 LSN**(`version_type=external`) → 같은 변경 재전달은 무시, 늦게 온 옛 변경은 ES 가 거절 | at-least-once(ADR 0006)를 ES 쪽에서 멱등으로. `processed_message` 같은 표가 필요 없다 |
| 5 | 삭제 | 상품은 지우지 않고 판매 중지(`on_sale`) → 문서에 남기고 검색에서 거른다. tombstone 이 오면 문서 삭제 | 4-1a 가 삭제 API 를 두지 않았다 |
| 6 | 처음 적재·다시 색인 | 커넥터 `snapshot.mode=initial` 로 기존 행(시드 포함) 적재. 색인 구조를 바꿀 때는 별칭(`products` → `products-v1`) + 새 색인 + Debezium 증분 스냅샷(signal 표)으로 다시 채운 뒤 별칭 전환 | 다시 색인 절차는 문서·스크립트로, 자동화는 하지 않음 |
| 7 | 한글 검색 | **ngram 분석기**(플러그인 없음, 공식 이미지 그대로) | nori(형태소)는 플러그인 설치 이미지를 따로 빌드해야 한다(release 이미지 11개째). 상품명 부분 일치에는 ngram 으로 충분, nori 는 한계에 적음 |
| 8 | 검색 기능 | 검색어(이름·설명), 가격 범위, 판매 중인 것만, 정렬(관련도·가격·최신), 페이지(최대 100). 로그인 없이 공개(상품 조회와 같음) | 자동완성·오타 교정·집계(facet)는 하지 않음 |
| 9 | ES 장애 | 검색만 503. 상품 목록(product-service)·주문은 그대로. 색인 소비자는 ES 가 돌아올 때까지 제자리 재시도(ADR 0012), 돌아오면 따라잡음 | 검색 결과 가격은 조금 늦을 수 있다 → 주문 때 4-1b 가 다시 확인(409)하므로 돈은 맞다 |
| 10 | 엔진·배포 | Elasticsearch 공식 이미지(단일 노드, 로컬은 보안 끔, 힙 512MB). Helm 차트에 StatefulSet 으로(ECK 오퍼레이터는 쓰지 않음). 라이선스가 걸리면 OpenSearch 로 바꿀 수 있게 클라이언트는 REST 수준만 | ECK 는 kind CI 에 무겁고 노드 하나면 이득이 작다 |
| 11 | 관측성 | 색인 지연(지금 − 원본 커밋 시각) 지표·경보, 소비자 lag 는 기존 경보 그대로 | 시나리오에서 "가격 변경 → 몇 초 안에 검색 반영"을 이 지표로 보인다 |
| 12 | 상품 관리 이력(audit) | product-service 안에서 **같은 트랜잭션**으로 `product_audit_log`(누가·언제·무엇: 등록·정보 수정·가격·판매 상태, 바뀐 값 전후)를 남김. 가격 이력(4-1a)은 그대로 두고 관리자 화면에서 함께 보여 줌 | CDC 로 만들 수도 있지만 "누가 바꿨는지"(JWT `sub`)가 테이블에 없어 열을 더 늘려야 한다. 가격 이력과 같은 방식이 단순 |

**PR 나누기 (추천)**

| PR | 범위 |
|---|---|
| 4-2a 기반 (완료, PR #82) | product_db 테이블 CDC 커넥터(compose 등록 스크립트·Strimzi `KafkaConnector`), 토픽 `product.cdc.products`, product_svc 에 replication 권한. AGENTS.md 키 규칙에 상품 id. ADR 0010 은 설계 PR(#81)에서. ES 는 4-2b 로(쓰는 search-service 와 같이 들어가야 확인할 수 있음) |
| 4-2b search-service (완료, PR #89) | ES 를 compose·Helm·CI 에 추가. 새 서비스(헥사고날, DB 없음, ES 가 저장소): CDC 소비 → 외부 버전으로 색인, 검색 API, Gateway `/api/search/**`, 색인 지연 지표. 시나리오 16(가격 변경·판매 중지가 몇 초 안에 검색에 반영, ES 중단 중 검색 503·상품 목록·주문 정상, 복구 뒤 따라잡음, 같은 변경 재전달 무시) |
| 4-2c 화면 (완료, PR #90) | 상품 목록에 검색창·필터·정렬, 결과에서 바로 담기·주문(가격은 주문 때 다시 확인) |
| 4-3a 관리 이력 (완료, PR #91) | product-service `product_audit_log`(등록·정보 수정·가격·판매 상태, 바꾼 사람 = JWT `sub`, 시각, 전·후 값을 열로). 상품 변경과 같은 트랜잭션, 값이 그대로인 요청은 남기지 않음. 기존 가격 이력은 V3 에서 가격만 옮김. 조회 `GET /admin/products/{id}/audit-log`(ADMIN, 최근부터, 페이지) |
| 4-3b 관리자 상품 화면 (완료, PR #94) | 관리자 상품 목록·수정(정보·가격·판매 상태)·상품별 관리 이력, Playwright |

**부담·한계로 적을 것**: ES 메모리(로컬 compose 전체가 약 1GB 늘어남, CI 러너는 여유 있음), 검색 결과는 최종 일관성(수 초 늦을 수 있음), ngram 이라 형태소 검색 아님, 단일 노드라 ES 고가용성 없음.

## 4-7. 후속 5 인증 재구성 설계 메모 (2026-11-25 사용자 결정)

화면을 **사용자·관리자·인증 3개 SPA** 로 나누고, auth-service 를 **Spring Authorization Server(OIDC IdP)** 로, Spring Cloud Gateway 를 **BFF(OAuth2 클라이언트 + 토큰 릴레이)** 로 바꾼다. 서비스(리소스 서버)의 JWT 검증은 그대로다. 흐름·저장소·시크릿·라우팅·PR 나누기는 **[인증 재구성 설계](auth-sas-bff-design.md)** 에 모았다. 시작 전에 그 문서를 읽는다.

**결정 요약**
- 구성: SPA(토큰 모름) → 같은 출처 `/api/**` → SCG(세션에 토큰 보관, `TokenRelay`) → 서비스(JWKS 검증). 로그인은 SCG `oauth2Login` → SAS(`auth` 호스트), SSO 세션으로 화면 사이 재로그인 없음
- 저장: 회원·클라이언트·인가 기록은 `auth_db`(JDBC), SSO 세션(auth)과 BFF 세션(gateway)은 Redis 두 개(접두어 분리)
- 토큰: `sub` = `long userId`, `roles` 클레임 유지, access 15분, refresh 는 SAS 기본 회전(ADR 0008 재사용 탐지는 들고 오지 않음: refresh 가 브라우저에 나가지 않음)
- 클라이언트 등록: **DCR 아님, 설정 기반 고정 등록**(`gateway-web`, `gateway-admin`). `client-secret` 은 외부 변수가 최우선, 없으면 앱 밖에서 생성(compose init 컨테이너, k8s 스크립트로 Secret, 호스트 로컬은 개발용 파일), 그래도 없으면 두 앱 모두 기동 실패. auth-service 는 시작할 때 DB 에 멱등 upsert(없으면 등록, `matches` 가 다르면 해시 갱신)
- 라우팅: 두 방식을 같은 빌드로 지원: **다른 도메인이면 경로는 같고 도메인만 다르게**(기본, `example.com`·`admin.`·`auth.`), **같은 도메인이면 경로로**(`example.com/`·`/admin/`·`/auth/`, 프록시가 앞 경로를 떼고 `X-Forwarded-Prefix`, SPA 는 nginx 가 `<base href>` 주입, 이때 BFF 세션·로그인 클라이언트는 하나). 앱은 항상 루트에서 돈다. 화면 쪽 `/api/**`·`/oauth2/authorization/**`·`/login/oauth2/code/**`·`/logout` 은 SCG, 인증 쪽 `/oauth2/**`·`/.well-known/**`·`/login`·`/signup`·`/csrf` 는 auth-service, 나머지는 화면. 로그인 화면은 `/signin`(화면), 처리는 `POST /login`(auth-service)으로 나눔. **로컬 개발**은 포트(`localhost:5173/5174/5175`) + Vite 프록시, **compose** 는 Caddy + `*.localhost`, **k8s** 는 Envoy `HTTPRoute`. 주소는 `AUTH_ISSUER`(공개)·`AUTH_INTERNAL_URL`(내부)·redirect URI 설정값으로만, 세션 쿠키 이름은 `GW_SESSION`·`AUTH_SESSION` 으로 분리(설계 문서 7절)
- 구현 전 보강(설계 문서 7-3·7-4·8절): 화면은 클라이언트 이름 없이 `/oauth2/authorization/auto` 로 로그인을 시작하고 SCG 가 `ADMIN_CLIENT_HOST` 로 `gateway-web`·`gateway-admin` 을 고른다(같은 빌드가 두 방식에서 돌게). auth-service CSRF 토큰은 쿠키가 아니라 세션 ① 에(SCG `XSRF-TOKEN` 과 겹침 방지). 앞단 프록시는 들어온 `X-Forwarded-Prefix` 를 지운다(위조 방지). 로그아웃은 `return_to` 로 돌아갈 홈을 고른다(경로 방식 관리자 → `/admin/`). nginx 는 시작 스크립트로 `<base href>` 를 넣고 `BASE_PATH` 는 `/` 로 끝나야 한다. 경로가 붙은 issuer 확인 테스트는 5-1. 화면 3개 유지는 사용자 결정(2026-11-25, SSO 시연·호스트 방식 관리자 격리)
- ADR 0008·0011 의 바뀌는 부분은 5-0 에서 ADR 0017 로 대체 표시(머지된 ADR 본문은 고치지 않음). 브리프에 없던 SAS·Caddy·SPA 전환은 사용자가 정했다(2026-11-25)

| PR | 범위 |
|---|---|
| 5-0 | [ADR 0017](adr/0017-auth-sas-gateway-bff-spa.md) (결정·대체 범위, ADR 0008 일부·0011 대체 표시) |
| 5-1 | auth-service → SAS (기존 `/auth/*` API 는 5-3 까지 유지) |
| 5-2 | SCG BFF(`oauth2Login`·`TokenRelay`·Redis 세션·CSRF·OIDC 로그아웃) |
| 5-3 | 사용자 SPA(`frontend/apps/web` + `packages/ui`), compose Caddy·시크릿 init, Helm 호스트 라우트, e2e. 끝나면 Next·기존 `/auth/*` 제거 |
| 5-4 | 인증 화면(`frontend/apps/auth`) |
| 5-5 | 관리자 SPA(`frontend/apps/admin`), SSO 확인 |
| 5-6 | 로그인 기기 관리 재구현, 시나리오 문서, 태그 |


- 결제 취소 응답(주문 취소)과 실제 PG 환불 사이에 시간 차가 있다(보정 주기 + 늦은 승인까지). 환불이 계속 실패하면 경고 로그와 경보(`PaymentRefundPending`). `UNKNOWN`이 오래가면 `PaymentResultUnknown`
- 보상 단계가 계속 실패하면 끝없이 재전송하고 경고 로그와 경보(`SagaCompensationStuck`)
- 멱등 기록 보존: processed_message 30일, Idempotency-Key 7일 뒤 지운다(②). 30일 넘게 지난 메시지를 DLT 에서 재처리하면 중복으로 걸러지지 않고 다시 처리된다(주문별 기록이 한 번 더 막는다, ADR 0006). 7일 지난 키로 다시 보내면 새 주문
- ~~주문 가격을 클라이언트가 보낸다~~ → 4-1b 에서 서버가 product-service 가격과 비교(다르면 409)하고 서버 가격을 스냅샷한다 (ADR 0016, 시나리오 14). 확인과 주문 트랜잭션 사이에 가격이 또 바뀌면 주문은 확인한 가격으로 들어간다(시나리오 14 설계 표)
- product-service 가 멈추면 새 주문이 503 (진행 중인 Saga 는 영향 없음, ADR 0016)
- PG 장애 동안 회로가 열리기 전(최소 10건)의 결제는 지금처럼 타임아웃 → UNKNOWN → 보정. 회로 상태는 인스턴스마다 따로(③, 시나리오 15)
- 주문 취소 API 없음 (Saga 가 끝나야 결과가 난다) → 4-4절 "정하고 미룬 것"
- 관리자가 새로 등록한 상품(id 1000~)은 inventory 에 재고 행이 없어 주문하면 재고 예약 실패 → 주문 취소. 재고 등록 API 는 아직 없다(재고는 inventory 소유, 상품 등록 이벤트로 만들지·관리자 API 로 넣을지는 ④ 에서 정한다)
- 스케줄러 relay를 여러 인스턴스로 띄우면 같은 키의 순서가 바뀔 수 있음 → Debezium 모드에서는 WAL 커밋 순서로 나가 해소 (ADR 0005)
- Debezium 모드에서 커넥터가 멈추면(또는 없는 토픽으로 라우팅되어 막히면) replication slot이 WAL을 붙잡아 디스크가 찬다. 관찰·heartbeat는 시나리오 5에서 끝냈고, slot 적체 경보는 `ReplicationSlotLag`. `max_slot_wal_keep_size` 상한은 유실 위험 때문에 두지 않음 (ADR 0004)
- 결제 장애 주입 API 는 ADMIN 역할만 (Gateway `/api/admin/payment-faults` + payment-service `/admin/**`, A-2)
- 인증: 이미 나간 access token 은 최대 15분 유효(즉시 차단 없음). 로그인 잠금은 아이디 기준이라 남이 내 계정을 15분 잠글 수 있음(IP 제한은 Gateway rate limit). 동시 refresh 유예(20초) 안의 옛 refresh token 은 access token 을 받을 수 있음(회전은 못 함) (ADR 0008)
- 분류되지 않은 영구 오류는 파티션을 막는다 (의도). `ConsumerLagHigh` 경보로 잡는다
- trace: 단계 타임아웃 재전송·결제 보정 후 응답은 원래 trace 에 잇지 못하고 새 trace 로 시작한다(주문 id 검색으로 모임). 이으려면 Saga 행에 처음 traceparent 를 저장 (ADR 0013)
- 수집기 Alloy 는 한 개(SPOF): 멈추면 메트릭·trace·로그가 같이 빈다. `MetricsPipelineDown` 경보로 알리는 데까지. 여러 개로 늘리려면 clustering(scrape 분산) + trace id 로 나누는 앞단(tail sampling) (ADR 0013 "메트릭도 Alloy 로")
- 경보 문턱값은 시연용(짧음). 경보 전달은 Alertmanager → Discord 웹훅 하나(PR #70). 웹훅 URL 을 넣지 않으면 receiver 가 `null` 이라 전달되지 않는다. 운영 값은 ADR 0013 표

## 6. 작업 방식 (매 PR 동일)

1. main에서 `feat/…` 등 브랜치 → 작업 → 바뀐 모듈 빌드·테스트 (Docker 켜고 Testcontainers 포함)
2. e2e 시나리오가 있으면 로컬에서 한 번 돌려 본다
3. PR → **Codex 리뷰(지적 없음) + CI 전부 통과 후** squash 머지. 지적이 나오면 고쳐 push하면 재리뷰가 자동으로 돈다
4. 단계가 끝나면 태그 (`v0.3-reliability`)

커밋·PR은 `klmin` 이름, 한국어, Conventional Commits. 설정·테스트에 실제 비밀값 금지.

## 7. 로컬에서 이어서 돌리기

```bash
docker compose -f infra/docker-compose.yml up -d --wait     # Postgres, Kafka, Kafka UI(8989), 토픽·DB 초기화, Tempo(3210, OTLP 4328)·Prometheus(9091)·Grafana(3030), Redis(6380)
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
scripts/smoke-tracing.sh                                    # 시나리오 11 (trace 는 Grafana http://localhost:3030 → Explore → Tempo)
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
- 커넥터를 등록한 채로 서비스를 스케줄러 모드로 띄우면 두 relay가 같은 메시지를 두 번 보낸다(id 같음 → 컨슈머가 걸러 결과는 같지만 의도한 상태가 아님). 로컬에서 모드를 바꿀 때는 서비스를 모두 내리고 `down -v`로 새로 시작하는 게 가장 단순하다
- 모드는 기동 때 정해진다(`@ConditionalOnProperty`, `OutboxWriter`). 서비스를 내리지 않고 바꾸려면 인스턴스를 하나씩 재시작하는 롤링이 되고, 그동안 두 모드가 섞인다. 규칙 하나: **`false` 인스턴스가 하나라도 떠 있거나, 그 인스턴스들이 쓴 메시지를 커넥터가 아직 다 발행하지 못한 동안 커넥터(와 slot)를 지우지 않는다.** 그 인스턴스는 넣고 바로 지우므로 WAL을 읽는 커넥터만 발행할 수 있다. 겹치는 구간의 중복은 컨슈머가 거른다
  - 스케줄러 → Debezium: 커넥터 등록 → 인스턴스를 하나씩 `false`로 교체 → outbox 0행 확인
  - Debezium → 스케줄러: 커넥터를 둔 채 인스턴스를 하나씩 `true`로 교체 → `false` 인스턴스가 모두 정상 종료된 뒤(진행 중 트랜잭션 완료) 그 서비스 DB에서 기준 위치를 기록: `select pg_current_wal_lsn()` → 커넥터가 그 위치까지 발행·확인할 때까지 기다림: `select confirmed_flush_lsn >= '<기준 위치>'::pg_lsn from pg_replication_slots where slot_name = '<서비스>_outbox_slot'` 이 `t` (heartbeat 10초마다 위치가 앞으로 간다) → 커넥터 삭제(`curl -X DELETE localhost:8088/connectors/<이름>`), 필요하면 아래처럼 slot 삭제
  - 적체가 "줄어드는 것"만 보고 지우면 안 된다. 일부만 읽은 상태일 수 있고, 남은 메시지는 outbox 행이 이미 지워져 스케줄러도 되살리지 못한다
  - 거꾸로(커넥터·slot을 먼저 지우고 인스턴스 교체) 하면 아직 `false`인 인스턴스가 그사이 쓴 메시지는 outbox에도 WAL을 읽을 slot에도 없어 **사라진다**
- 커넥터를 지워도 slot은 DB에 남아 WAL을 붙잡는다. 다시 등록하지 않을 거면 `select pg_drop_replication_slot('<서비스>_outbox_slot')`

- 실행 jar(`java -jar`)는 JDK 25가 필요하다. PATH의 java가 더 낮으면 JDK 25 경로로 직접 실행하거나 `JAVA_HOME`을 지정한다 (`bootRun`은 Gradle 툴체인이 알아서 맞춘다)
- Windows Git Bash에서 `docker compose exec … /opt/kafka/…`는 경로가 윈도 경로로 바뀌므로 `MSYS_NO_PATHCONV=1` (스크립트에는 이미 들어 있다)
- 로컬 Kafka·DB에 이전 단계 데이터가 남아 있으면 시나리오 3이 "응답 재발행"으로 실패할 수 있다(ADR 0006 배포 경계). 깨끗이 다시 하려면 `docker compose -f infra/docker-compose.yml down -v` (로컬 데이터 삭제)
