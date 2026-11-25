# CI/CD 워크플로

`.github/workflows/` 의 워크플로가 언제 무엇을 하는지 정리한다. 워크플로를 바꾸면 이 문서도 같이 고친다.

## 한눈에

| 이벤트 | 도는 것 |
|---|---|
| 기능 브랜치 push (PR 없음) | 없음. `push` 트리거는 전부 `main` 한정 |
| PR 열기 / PR 에 push | 바뀐 경로에 맞는 CI(아래 표). PR 에 추가 push 하면 Codex 재리뷰 요청 |
| main 머지 (= main push) | 같은 CI 를 main 에서 한 번 더 + `release`(이미지 → `release/eks` → EKS ArgoCD) |
| 정기 | CodeQL 매주 월 03:00 KST, Dependabot 매주 |

모든 워크플로에 `concurrency` 가 있다. CI 는 같은 브랜치에 연달아 push 하면 앞 실행을 취소하고 마지막 것만 돈다. `release` 만 취소하지 않고 한 번에 하나씩 돈다.

## 워크플로별

| 워크플로 | 체크 이름 | 도는 조건 (PR·main push 공통 경로) | 하는 일 |
|---|---|---|---|
| `backend` | backend | `services/` `libs/` `tools/` `build-logic/` `gradle/` `*.gradle.kts` `gradlew` | 바뀐 서비스·도구만 빌드·테스트(Testcontainers 포함). `libs/`·`build-logic/`·Gradle 설정이 바뀌면 전체 |
| `e2e` | saga (scheduler), saga (debezium) | `services/` `libs/` `tools/` `build-logic/` `infra/` `scripts/` | compose + 서비스 8개를 띄우고 시나리오 스모크. relay 두 모드(libs/outbox 스케줄러, Debezium) matrix |
| `browser-e2e` | playwright | `frontend/` `services/` `libs/` `build-logic/` `infra/` | 인프라 + 서비스 + Next(production)를 띄우고 Playwright 화면 시나리오 |
| `frontend` | frontend | `frontend/` | format·lint·typecheck·Vitest·`next build`·Docker 이미지 빌드 확인 |
| `infra` | compose-up | `infra/` | compose 가 뜨는지, 토픽·Connect 플러그인·replication 권한·서비스 DB 분리(8개)·Redis 확인 |
| `k8s` | kind, argocd | `deploy/` `services/` `libs/` `build-logic/` `frontend/` `infra/postgres/` 관측성 설정(`infra/alloy·tempo·loki·prometheus·grafana·alertmanager/`) `scripts/k8s-*.sh` `scripts/smoke-tracing.sh` | 차트의 infra 사본 diff, kind 에 Helm 차트(Strimzi 포함)로 올리고 스모크·Debezium·관측성 확인(kind). 같은 구성을 ArgoCD 로 sync(argocd) |
| `codeql` | analyze (java-kotlin), analyze (javascript-typescript), analyze (actions) | `docs/**`·`*.md` 만 바뀐 경우 제외 전부 (+ 주간 schedule) | 정적 분석(security-and-quality). javascript-typescript 는 `frontend/` |
| `Codex review on push` | request-review | 열린 PR 에 push(`synchronize`). draft·fork·Dependabot push 제외 | `@codex review` 댓글로 재리뷰 요청. PR 을 열 때·ready 로 바꿀 때는 Codex 가 스스로 리뷰 |
| `release` | images (amd64), images (arm64), manifest | main push, `docs/**`·`*.md` 만 바뀐 경우 제외 (+ 수동 실행) | 아래 "배포" |

각 워크플로 파일 자기 자신이 바뀌어도 그 워크플로가 돈다. docs 만 바꾼 PR 은 CI 가 돌지 않고 Codex 리뷰만 받는다.

## 머지

- `main` 은 ruleset 으로 보호한다: 직접 push·삭제·force push 금지, PR 로만 머지(squash)
- ruleset 에 필수 status check 는 없다. 경로로 건너뛴 워크플로가 머지를 막지 않는 대신, **머지 조건(CI 전부 통과 + 마지막 커밋에 Codex 지적 없음)은 사람이 확인한다** ([PROGRESS 0절](PROGRESS.md))
- 체크를 필수로 바꾸려면 경로 필터(`paths`, `paths-ignore`)를 job 안의 경로 판단으로 바꿔야 한다. 건너뛴 워크플로의 필수 체크는 대기 상태로 남아 머지를 막는다
- **감수한 것: 배포가 main CI 결과를 기다리지 않는다.** `release` 는 main 의 CI 와 동시에 돌고, main CI 는 다음 머지가 오면 취소된다(concurrency). 그래서 배포 전 검증은 PR CI 하나다. squash 머지라 PR 이 main 최신이 아니었으면 합친 결과는 머지 뒤 main CI 에서야 처음 돈다. 막으려면 위처럼 필수 체크로 바꾸고 ruleset 의 "브랜치 최신 상태 요구"를 켠다

## 배포 (`release`)

```
main 머지 → images (amd64·arm64 러너에서 각각): bootJar → 이미지 10개 GHCR push (<커밋 SHA>-<아키텍처>)
         → manifest: 두 아키텍처를 <커밋 SHA> 하나로 묶고 확인 → release/eks 브랜치를 그 커밋으로 → EKS 의 ArgoCD 가 sync
```

- 이미지: 서비스 8개 + `frontend` + `connect`, `ghcr.io/<owner>/commerce-saga/<이름>:<커밋 SHA>`. 테스트는 PR CI 가 이미 돌렸으므로 bootJar 만
- 이미지는 linux/amd64 + linux/arm64 멀티 아키텍처. 아키텍처마다 그 아키텍처 러너(`ubuntu-latest`, `ubuntu-24.04-arm`)에서 네이티브로 빌드한다. 노드(x86, Graviton)는 자기 CPU 에 맞는 쪽을 받는다 ([ADR 0014](adr/0014-k8s-deployment.md) 멀티 아키텍처)
- 두 아키텍처가 모두 묶인 뒤에만 `release/eks` 를 옮긴다. 그래서 이미지가 없는 커밋은 배포되지 않는다
- EKS 의 ArgoCD 는 `release/eks` 를 본다(`deploy/argocd/eks/root.yaml`). CI 에는 클러스터 권한이 없다
- 머지가 몰리면 한 번에 하나씩 돈다. 대기 중인 실행은 GitHub 가 최신 것 하나만 남기므로 중간 커밋은 배포되지 않을 수 있다
- 태그가 커밋이라 서비스 하나만 바뀌어도 이미지 10개를 다시 빌드하고 앱 Pod 를 모두 새 태그로 롤링한다. Postgres·Kafka 등 차트 설정이 그대로인 것은 그대로 둔다
- 로컬·CI 검증용 ArgoCD(`deploy/argocd/root.yaml`)는 `main` 을 본다. 자세한 구성은 [deploy/README.md](../deploy/README.md), 결정은 [ADR 0014](adr/0014-k8s-deployment.md)

**롤백**: 이미지가 남아 있는 옛 커밋으로 브랜치를 되돌린다.

```bash
git push origin <옛 커밋>:refs/heads/release/eks --force
```

## Dependabot

`.github/dependabot.yml`. 매주 Gradle(루트·`build-logic`, Spring 묶음과 나머지 묶음), `frontend/` npm(런타임 묶음과 개발 도구 묶음), GitHub Actions, `infra/` docker-compose 이미지(PostgreSQL 메이저 제외) 업데이트 PR 을 연다. Dependabot 이 push 한 커밋에는 secret 이 전달되지 않아 Codex 재리뷰 요청은 건너뛴다.

Dockerfile 베이스 이미지(`eclipse-temurin:25-jre`, `node:26-alpine`)는 대상에 넣지 않았다. 메이저 태그라 이미지를 다시 빌드할 때마다 최신 패치를 받는다.
