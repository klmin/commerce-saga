# CI/CD 워크플로

`.github/workflows/` 의 워크플로가 언제 무엇을 하는지 정리한다. 워크플로를 바꾸면 이 문서도 같이 고친다.

## 한눈에

| 이벤트 | 도는 것 |
|---|---|
| 기능 브랜치 push (PR 없음) | 없음. `push` 트리거는 전부 `main` 한정 |
| PR 열기 / PR 에 push | 바뀐 경로에 맞는 CI(아래 표). PR 에 추가 push 하면 Codex 재리뷰 요청 |
| main 머지 (= main push) | 같은 CI 를 main 에서 한 번 더 + `release`(이미지 → `release/eks` → EKS ArgoCD) |
| 정기 | `k8s` 매일 04:00 KST, CodeQL 매주 월 03:00 KST, Dependabot 매주 |
| 워크플로가 끝날 때·PR 열림·머지 | `notify` 가 Discord 로 알림 (아래 "알림") |

모든 워크플로에 `concurrency` 가 있다. CI 는 PR 에 연달아 push 하면 앞 실행을 취소하고 마지막 것만 돈다. **main 의 CI 는 취소하지 않는다**(실행마다 그룹이 다르다): `release` 가 커밋마다 그 커밋의 main CI 결과를 기다리기 때문이다. `release` 는 취소하지 않고 한 번에 하나씩 돈다.

## 워크플로별

| 워크플로 | 체크 이름 | 도는 조건 (PR·main push 공통 경로) | 하는 일 |
|---|---|---|---|
| `backend` | backend | `services/` `libs/` `tools/` `build-logic/` `gradle/` `*.gradle.kts` `gradlew` | 바뀐 서비스·도구만 빌드·테스트(Testcontainers 포함). `libs/`·`build-logic/`·Gradle 설정이 바뀌면 전체 |
| `e2e` | saga (scheduler), saga (debezium) | `services/` `libs/` `tools/` `build-logic/` `infra/` `scripts/` | compose + 서비스 9개를 띄우고 시나리오 스모크. relay 두 모드(libs/outbox 스케줄러, Debezium) matrix |
| `browser-e2e` | playwright | `frontend/` `services/` `libs/` `build-logic/` `infra/` | 인프라 + 서비스 + Next(production)를 띄우고 Playwright 화면 시나리오 |
| `frontend` | frontend | `frontend/` | format·lint·typecheck·Vitest·`next build`·Docker 이미지 빌드 확인 |
| `infra` | compose-up | `infra/` | compose 가 뜨는지, 토픽·Connect 플러그인·replication 권한·서비스 DB 분리(8개)·Redis 확인 |
| `k8s` | kind, argocd | `deploy/` `services/` `libs/` `build-logic/` `frontend/` `infra/postgres/` 관측성 설정(`infra/alloy·tempo·loki·prometheus·grafana·alertmanager/`) `scripts/k8s-*.sh` `scripts/smoke-tracing.sh` (+ 매일 schedule, 수동) | 차트의 infra 사본 diff, kind 에 Helm 차트(Strimzi 포함)로 올리고 스모크·Debezium·관측성 확인(kind). 같은 구성을 ArgoCD 로 sync(argocd). **argocd 는 PR 에서 `deploy/`·`scripts/k8s-*`·이 워크플로가 바뀔 때만** 돌고(서비스 코드만 바뀐 PR 은 kind 가 같은 차트로 확인), main push·매일·수동 실행에서는 항상 돈다 |
| `codeql` | analyze (java-kotlin), analyze (javascript-typescript), analyze (actions) | `docs/**`·`*.md` 만 바뀐 경우 제외 전부 (+ 주간 schedule) | 정적 분석(security-and-quality). javascript-typescript 는 `frontend/` |
| `Codex review on push` | request-review | 열린 PR 에 push(`synchronize`). draft·fork·Dependabot push 제외 | `@codex review` 댓글로 재리뷰 요청. PR 을 열 때·ready 로 바꿀 때는 Codex 가 스스로 리뷰 |
| `release` | ci, plan, images (amd64), images (arm64), manifest | main push, `docs/**`·`*.md` 만 바뀐 경우 제외 (+ 수동 실행) | 아래 "배포" |
| `notify` | pr-event, workflow-result | 위 워크플로가 끝날 때(`workflow_run`), PR 열림·ready·머지·닫힘 | 아래 "알림" |

각 워크플로 파일 자기 자신이 바뀌어도 그 워크플로가 돈다. docs 만 바꾼 PR 은 CI 가 돌지 않고 Codex 리뷰만 받는다.

## 머지

- `main` 은 ruleset 으로 보호한다: 직접 push·삭제·force push 금지, PR 로만 머지(squash)
- ruleset 에 필수 status check 는 없다. 경로로 건너뛴 워크플로가 머지를 막지 않는 대신, **머지 조건(CI 전부 통과 + 마지막 커밋에 Codex 지적 없음)은 사람이 확인한다** ([PROGRESS 0절](PROGRESS.md))
- 체크를 필수로 바꾸려면 경로 필터(`paths`, `paths-ignore`)를 job 안의 경로 판단으로 바꿔야 한다. 건너뛴 워크플로의 필수 체크는 대기 상태로 남아 머지를 막는다
- **배포는 main CI 가 통과한 커밋만 한다.** squash 머지라 PR 이 main 최신이 아니었으면 합친 결과는 머지 뒤 main CI 에서야 처음 돈다. 그래서 `release` 의 첫 잡(`ci`, `scripts/release-ci-gate.sh`)이 **마지막 배포 이후의 main 커밋들**의 CI(위 표의 CI 워크플로)가 끝나길 기다리고, **커밋 하나하나**의 실행(다시 돌렸으면 마지막 실행)이 모두 통과해야 다음으로 간다. 실패·취소면 이미지도 만들지 않고 멈춘다. 이번 커밋만 보면 부족하다: CI 는 경로로 골라 돌고 backend 는 바뀐 모듈만 빌드해서, A 에서 깨진 모듈이 B 에서는 다시 검증되지 않는다. 같은 이유로 B 의 통과가 A 의 실패를 대신하지 않는다. 실행이 아직 API 에 안 보이는 경우를 놓치지 않으려고, 문서만 바꾼 게 아닌 커밋은 codeql 실행(그런 push 마다 항상 돈다)이 보일 때까지 기다린다. 머지 자체는 막지 않는다(머지를 막으려면 위처럼 필수 체크 + ruleset 의 "브랜치 최신 상태 요구")

## 배포 (`release`)

```
main 머지 → ci: 마지막 배포 이후 main 커밋들의 CI 가 끝나고 통과할 때까지 대기 (실패면 멈춤)
         → plan: 마지막 배포 이후 바뀐 이미지 고르기 (scripts/release-plan.sh) → release-tags.yaml
         → images (amd64·arm64 러너에서 각각, 바뀐 것만): bootJar → GHCR push (<커밋 SHA>-<아키텍처>)
         → manifest: 두 아키텍처를 <커밋 SHA> 하나로 묶고, release-tags.yaml 의 이미지가 모두 있는지 확인
                     → release/eks 에 release 커밋(그 main 커밋 + release-tags.yaml) → EKS 의 ArgoCD 가 sync
```

- 이미지: 서비스 9개 + `frontend` + `connect`, `ghcr.io/<owner>/commerce-saga/<이름>:<커밋 SHA>`. 테스트는 PR CI 와 main CI(`ci` 잡이 통과를 확인)가 돌리므로 bootJar 만
- **바뀐 이미지만 빌드·배포한다** ([ADR 0014](adr/0014-k8s-deployment.md) "바뀐 서비스만 배포"). `plan` 이 **마지막으로 배포된 main 커밋**(release/eks 의 `release-tags.yaml` 에 적힌 `releasedCommit`) → 이번 커밋 사이에 바뀐 경로로 고른다

  | 바뀐 경로 | 다시 빌드하는 이미지 |
  |---|---|
  | `services/<이름>/` | 그 서비스 |
  | `libs/`, `build-logic/`, `gradle/`, `settings.gradle.kts`, `gradlew`, `deploy/docker/service.Dockerfile` | 백엔드 9개 전부 |
  | `frontend/` | frontend |
  | `deploy/docker/connect.Dockerfile` | connect |
  | `release.yml`, `scripts/release-plan.sh` | 전부 |
  | 그 밖(차트 `deploy/` 등) | 없음. release 커밋만 만들어 ArgoCD 가 차트 변경을 sync |

- 바뀐 이미지의 태그 = 이번 main 커밋 해시, 나머지는 이전 태그 그대로. 차트는 이미지별 태그(`image.tags.<이름>`)를 쓰므로 **태그가 바뀐 Deployment 만 롤링**된다. 차트 설정(env 등)이 바뀐 서비스는 이미지와 상관없이 롤링된다
- 직전 커밋이 아니라 마지막 배포 커밋과 비교하므로, 머지가 몰려 중간 실행이 건너뛰어지거나 release 가 실패해도 다음 실행이 그 사이 변경을 모두 잡는다. Actions 수동 실행에서 `all` 을 켜면 전부 다시 빌드한다
- 이미지는 linux/amd64 + linux/arm64 멀티 아키텍처. 아키텍처마다 그 아키텍처 러너(`ubuntu-latest`, `ubuntu-24.04-arm`)에서 네이티브로 빌드한다. 노드(x86, Graviton)는 자기 CPU 에 맞는 쪽을 받는다 ([ADR 0014](adr/0014-k8s-deployment.md) 멀티 아키텍처)
- `release-tags.yaml` 의 모든 이미지가 두 아키텍처로 GHCR 에 있는지 확인한 뒤에만 `release/eks` 를 옮긴다. 그래서 이미지가 없는 커밋은 배포되지 않는다
- release 커밋은 부모가 (이전 release 커밋, 이번 main 커밋)이라 `release/eks` 는 force push 없이 앞으로만 간다. `git log --first-parent origin/release/eks` 가 배포 이력이다
- EKS 의 ArgoCD 는 `release/eks` 를 본다(`deploy/argocd/eks/root.yaml`). `release-tags.yaml` 은 apps 차트(`templates/commerce-saga.yaml`)가 `.Files` 로 직접 읽어서 이미 등록된 root 를 다시 apply 하지 않아도 된다. CI 에는 클러스터 권한이 없다
- 머지가 몰리면 한 번에 하나씩 돈다. 대기 중인 실행은 GitHub 가 최신 것 하나만 남긴다(건너뛴 커밋의 변경은 다음 실행에 포함된다)
- main CI 가 실패하면 그 뒤 머지의 release 도 멈춘다(실패한 커밋이 범위에 남아 있다). 일시 실패면 그 CI 를 다시 돌려 통과시킨 뒤 release 를 다시 돌린다(Re-run failed jobs). 뒤 커밋이 고쳤다면 고친 커밋의 CI 통과를 확인하고 Actions 에서 release 를 수동 실행하며 `ci_from` = 고친 커밋(그 커밋부터만 판정)
- 로컬·CI 검증용 ArgoCD(`deploy/argocd/root.yaml`)는 `main` 을 보고 `image.tags` 없이 공통 태그를 쓴다. 자세한 구성은 [deploy/README.md](../deploy/README.md), 결정은 [ADR 0014](adr/0014-k8s-deployment.md)

**롤백**: 옛 release 커밋으로 브랜치를 되돌린다. 그 커밋의 `release-tags.yaml` 이 가리키는 이미지가 GHCR 에 남아 있다.

```bash
git log --first-parent --oneline origin/release/eks     # release 커밋 목록
git push origin <옛 release 커밋>:refs/heads/release/eks --force
```

되돌린 뒤 다음 release 는 그 옛 release 커밋을 기준으로 비교한다(되돌린 사이의 변경도 다시 빌드한다).

## 알림 (`notify` → Discord)

채널(웹훅)은 둘이다. 사람이 바로 손대야 하는 것과 진행 상황만 보내고, PR 의 체크 하나하나는 보내지 않는다.

| 채널 (저장소 시크릿) | 보내는 것 |
|---|---|
| `DISCORD_WEBHOOK_MAIN` | main push·매일·수동 실행의 워크플로 실패(실패한 잡, 실행 링크), `release` 성공(배포된 커밋 → `release/eks`) |
| `DISCORD_WEBHOOK_PR` | PR 열림·머지·닫힘(draft 제외), PR 의 워크플로 실패, 커밋 하나의 체크가 모두 끝나고 실패가 없을 때 "모든 체크 통과" 한 번 |

- 웹훅 URL 은 저장소 시크릿에만 둔다(URL 만 있으면 누구나 그 채널에 쓸 수 있다). 없으면 그 채널 알림만 건너뛴다. 넣기: `gh secret set DISCORD_WEBHOOK_MAIN` (값은 입력창으로)
- 실패 알림 앞의 멘션(`@here`, `<@&역할ID>`)은 저장소 변수 `DISCORD_FAILURE_MENTION` (선택)
- "모든 체크 통과"는 그 커밋의 PR 워크플로 실행(위 표의 워크플로만, 워크플로마다 가장 최근 실행)을 조회해 남은 것도 실패도 없을 때 보내고, 커밋 status `notify/all-passed` 로 한 번만 보냈음을 남긴다. 조회가 실패하면 보내지 않는다. 같은 커밋의 판정은 `concurrency` 로 한 번에 하나씩(실패 알림은 실행마다 따로라 대기열에서 사라지지 않는다)
- `workflow_run` 은 main 에 있는 `notify.yml` 기준으로 돈다. 이 파일을 바꾼 PR 은 머지 뒤부터 적용된다
- 포크 PR 은 알리지 않는다(PR 이벤트는 시크릿이 없고, 워크플로 결과는 PR 번호가 비어 있다). 운영 경보(Alertmanager → Discord)는 별도 웹훅이라 채널을 나누는 편이 낫다

### EKS 배포 결과 (ArgoCD Notifications → Discord)

`release` 성공 알림은 `release/eks` 에 커밋을 얹은 시점이다. 클러스터에 실제로 반영됐는지는 클러스터 안의 ArgoCD 가 보낸다
(CI 에는 클러스터 권한이 없다). 설정은 [deploy/argocd/notifications.yaml](../deploy/argocd/notifications.yaml), 구독은 `commerce-saga` Application 에만 건다(root 까지 걸면 release 마다 두 번 온다).

| 트리거 | 언제 | 내용 |
|---|---|---|
| `on-deployed` | 새 revision 이 sync 되고 Healthy (revision 마다 한 번) | "ArgoCD 배포 완료", release 커밋 링크 |
| `on-sync-failed` | sync 실패(재시도 20번까지 끝남) | 단계, revision, ArgoCD 메시지 |
| `on-health-degraded` | Application 이 Degraded | revision, health |

- 웹훅 URL 은 저장소가 아니라 클러스터 Secret `argocd/argocd-notifications-secret` 의 `discord-webhook-url` 에 둔다. 넣고 켜기: `read -rs ARGOCD_DISCORD_WEBHOOK && export ARGOCD_DISCORD_WEBHOOK && scripts/eks-argocd.sh` (Secret 이 있으면 다음 실행부터는 주지 않아도 켜진 채)
- 채널은 `DISCORD_WEBHOOK_MAIN` 과 같은 웹훅을 써도 된다(release 성공 → 배포 완료가 이어서 온다)
- CI(`k8s` 의 argocd 잡)는 같은 설정으로 켜고 Discord 대신 클러스터 안 수신 Pod 로 보내, 배포 완료 알림이 JSON 으로 오고 revision 이 맞는지 본다

## Dependabot

`.github/dependabot.yml`. 매주 Gradle(루트·`build-logic`, Spring 묶음과 나머지 묶음), `frontend/` npm(런타임 묶음과 개발 도구 묶음), GitHub Actions, `infra/` docker-compose 이미지(PostgreSQL 메이저 제외) 업데이트 PR 을 연다. Dependabot 이 push 한 커밋에는 secret 이 전달되지 않아 Codex 재리뷰 요청은 건너뛴다.

Dockerfile 베이스 이미지(`eclipse-temurin:25-jre`, `node:26-alpine`)는 대상에 넣지 않았다. 메이저 태그라 이미지를 다시 빌드할 때마다 최신 패치를 받는다.
