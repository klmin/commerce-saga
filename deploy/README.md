# k8s 배포 (후속 3)

로컬 개발은 지금처럼 `infra/docker-compose.yml` + `java -jar` 로 한다. 이 디렉터리는 같은 구성을 k8s 에 올리는 방법이다.
클러스터를 상시 띄우지 않고, PR 마다 CI(`.github/workflows/k8s.yml`)가 kind 에 올려 스모크를 돈다.
운영 환경에서 구성 요소별로 무엇을 더 해야 안정적인지(이중화·저장소·경보)는 [docs/k8s-operations.md](../docs/k8s-operations.md).
로컬·compose·k8s·ArgoCD·EKS 가 각각 무엇을 읽어 어떻게 뜨는지, Helm 차트·values·ArgoCD Application 이 어떻게 이어지는지는 [docs/deployment-environments.md](../docs/deployment-environments.md).

| 경로 | 내용 |
|---|---|
| `docker/service.Dockerfile` | 백엔드 서비스 공용 이미지. bootJar 를 받아 Spring Boot 레이어로 풀어 실행(비 root) |
| `helm/commerce-saga/` | 차트 하나: Postgres(StatefulSet, 서비스별 DB·계정), Redis, Kafka(Strimzi `Kafka`·`KafkaNodePool`·`KafkaTopic`), 서비스 8개, 화면 |

## kind 에 올리기

필요: Docker, [kind](https://kind.sigs.k8s.io/), kubectl, Helm 4, JDK 25(Gradle 툴체인).

```bash
kind create cluster --name commerce-saga
scripts/k8s-deploy.sh            # 이미지 빌드 → kind 에 넣기 → Strimzi → 차트, 모두 준비될 때까지 기다림
scripts/k8s-port-forward.sh      # compose 때와 같은 로컬 포트 (8081~8086, gateway 8000, 화면 SPA 5173·5174(정적 파일만), Grafana 3030·Tempo 3210·Loki 3110·Prometheus 9091)
scripts/smoke-saga.sh            # 기존 스모크를 그대로
scripts/k8s-port-forward.sh stop
kind delete cluster --name commerce-saga
```

compose 로 띄운 서비스와 포트가 겹치므로 둘 중 하나만 띄운다.

## 결정 (K-1)

- **Kafka 버전**: Strimzi 1.3.1 이 지원하는 마지막 버전 4.3.2. 서비스의 클라이언트는 4.4.0(compose 브로커와 같음)이고 4.3 브로커와 호환된다
- **오퍼레이터는 Strimzi 하나**. Postgres·Redis 는 차트 안의 일반 StatefulSet·Deployment 로 둔다(compose 와 같은 단일 인스턴스). 운영이면 관리형 DB 나 오퍼레이터(CloudNativePG 등)로 바꿀 자리
- **설정은 application.yml 의 환경변수를 채우기만** 한다. 서비스마다 자기 DB 계정 Secret 만 받는다(다른 서비스 DB 비밀번호를 갖지 않음). 비밀번호는 모두 로컬 개발용 값
- **Postgres 초기화 SQL** 은 `infra/postgres/init` 의 사본(`helm/commerce-saga/files`). Helm 은 차트 밖 파일을 읽지 못한다. CI 가 두 파일이 같은지 검사한다
- **relay**: K-1 은 스케줄러 relay 로 시작했고, K-2 부터 배포 기준인 Debezium(ADR 0004)이 기본이다(아래)
- **trace**: K-1 은 클러스터에 Tempo 가 없어 `TRACING_SAMPLING_PROBABILITY=0` 으로 시작했고, 지금은 차트가 관측성을 같이 올려 서비스가 trace·로그를 alloy 로 보낸다(아래 "관측성". 끄면 `observability.enabled=false`)
- **auth-service 서명 키**: Secret `auth-jwt-key`(`private.pem` PKCS#8, `kid`)를 읽기만 한다. 로컬 k8s 는 차트의 hook Job(Helm `pre-install,pre-upgrade` = Argo CD PreSync)이 없을 때만 한 번 만들고 그 뒤로는 그대로 둔다. Secret 은 차트에 없어 Argo CD 가 관리하지 않는다(일부러, sync 로 덮어쓰거나 지우지 않게). EKS 는 외부 비밀 저장소(External Secrets 등)가 같은 이름의 Secret 을 채우고 `authJwtKey.generate=false`. 모든 Pod 가 같은 키라 `RollingUpdate`(`maxUnavailable: 0`)로 무중단 배포한다. Secret 을 지우면 다음 sync 때 새 키 → 그 전 access token 무효(refresh 로 복구). 확인: `kubectl -n commerce get secret auth-jwt-key -o jsonpath={.data.kid} | base64 -d` (ADR 0014 "auth-service 서명 키")
- **OAuth 클라이언트 시크릿**(후속 5, ADR 0017): Secret `oauth-client-secrets`(키 `gateway-web`·`gateway-admin`)를 auth-service(와 5-2 부터 Gateway)가 `GATEWAY_*_CLIENT_SECRET` 으로 읽는다. 위 서명 키와 같은 hook Job 이 없을 때만 만들고 Argo CD 가 관리하지 않는다. 외부 비밀 저장소가 채우면 `oauthClientSecrets.generate=false`. 개발용 시크릿 파일은 끄므로(`OAUTH_CLIENT_SECRET_DEV_DIR=""`) Secret 이 없으면 auth-service 가 기동 실패한다(조용히 만들면 Gateway 와 값이 어긋남). Secret 을 지우면 새 값이 생기고 auth-service·Gateway 를 다시 띄워야 맞는다
- **Pod 종료**: 서비스·화면은 `preStop` 5초 대기(`sleep` 핸들러, k8s 1.30+) 뒤 SIGTERM, 유예 40초(`gracefulTermination`). 종료가 시작되면 Endpoints 에서 빠지는 것과 SIGTERM 이 동시라, 대기가 없으면 Gateway·Envoy 가 옛 주소를 지우기 전에 앱이 연결을 닫아 롤아웃 중 요청이 실패한다(Gateway 는 연결 타임아웃 30초 뒤 500). Spring 은 graceful shutdown(기본)으로 처리 중인 요청을 끝낸다
- **이미지**: kind 노드에 직접 넣는다(`kind load docker-image`). 레지스트리에 올리면 `image.registry` 만 채운다

## 결정 (K-2, Debezium)

- **Strimzi `KafkaConnect` 하나 + 서비스별 `KafkaConnector` 5개**(order·inventory·point·payment·auth). 커넥터 설정은 compose 의 `infra/connect/<서비스>-outbox.json` 과 같다(slot·publication 이름, Outbox Event Router, heartbeat). 서비스는 `OUTBOX_RELAY_ENABLED=false`
- **Connect 이미지** `docker/connect.Dockerfile`: Strimzi Kafka 이미지(1.3.1, Kafka 4.3.2) + Debezium Postgres 플러그인(compose 와 같은 버전·SHA-512). Strimzi 의 `build`(클러스터에서 빌드해 레지스트리에 올림)는 kind 에 레지스트리가 없어 쓰지 않는다
- **DB 비밀번호**: Connect 컨테이너가 각 서비스 DB Secret 을 env 로 받고, 커넥터는 `${env:<DB>_DB_PASSWORD}`(EnvVarConfigProvider)로 읽는다. 커넥터 CR 에 비밀번호를 쓰지 않는다
- **heartbeat 토픽**(`__debezium-heartbeat.<커넥터>`)은 브로커 자동 생성을 껐으므로 `KafkaTopic` 으로 만든다
- `debezium.enabled=false` 면 KafkaConnect 없이 서비스의 스케줄러 relay 로 돈다
- CI 확인: 커넥터 5개 Ready, 스모크 뒤 outbox 5개가 비어 있음(넣고 바로 지움), 활성 replication slot 5개

## 관측성 (Grafana·Tempo·Loki·Prometheus)

차트가 alloy·tempo·loki·prometheus·alertmanager·kafka-exporter·grafana 를 같이 올린다(`observability.enabled`, 기본 true, ADR 0014 "관측성"). 밖으로는 열지 않으니 `scripts/k8s-port-forward.sh` 로 연결해서 Grafana http://localhost:3030 → Explore(Tempo, span 의 Related logs → Loki) 또는 대시보드 commerce-saga 를 본다. 설정 파일은 `files/observability` 에 있는 `infra/` 의 사본이라, 바꿀 때는 `infra/` 를 고치고 복사한다(CI 가 diff 로 확인)

메트릭은 alloy 가 모두 긁어 Prometheus 로 보낸다(ADR 0013 "메트릭도 Alloy 로"). 서비스·kafka-exporter 에 더해 tempo·loki·prometheus·alloy 자신과, k8s 에서는 이 namespace 컨테이너의 메모리·limit·CPU(노드 kubelet, `kubernetes.alloy`)도 들어온다. 그래서 Prometheus 의 `/targets` 는 비어 있고, 수집 상태는 `up{job="services"}` 같은 쿼리나 `kubectl -n commerce port-forward svc/alloy 12346:12345` 뒤 Alloy UI 로 본다. 컨테이너가 limit 에 가까우면 `ContainerMemoryNearLimit`, alloy 가 멈추면 `MetricsPipelineDown` 경보가 뜬다. alloy 는 한 개라 멈추면 메트릭·trace·로그가 같이 빈다(SPOF, 여러 개로 늘리는 방법은 ADR 0013)

### 경보 알림 (Alertmanager → Discord)

Prometheus 가 firing 경보를 alertmanager 로 보내고, alertmanager 가 심각도별로 묶어 Discord 웹훅으로 보낸다(ADR 0013 "경보 전달"). critical 은 바로·30분마다 다시, warning 은 2분 모았다가 보내고, 풀리면 `[RESOLVED]` 를 보낸다. 웹훅 URL 은 비밀값이라 차트·Git 에 넣지 않고 Secret 으로 만든다. Secret 이 없으면 null receiver 로 떠서 경보는 alertmanager UI 에서만 보인다

```bash
# Discord 채널 → 설정 → 연동 → 웹후크 → URL 복사
kubectl -n commerce create secret generic alertmanager-discord --from-literal=webhook-url='https://discord.com/api/webhooks/…'
kubectl -n commerce rollout restart deploy/alertmanager      # URL 은 기동 때 읽는다
kubectl -n commerce logs deploy/alertmanager | head -1       # "Discord 웹훅으로 보낸다"
```

Secret 이름·키는 `observability.alertmanager.discordWebhookSecret` 으로 바꾼다. ArgoCD 는 이 Secret 을 관리하지 않으므로(차트에 없음) 직접 만든 것이 sync 로 지워지지 않는다. 테스트 경보는 `scripts/k8s-port-forward.sh` 뒤 `curl -X POST localhost:9095/api/v2/alerts -H 'Content-Type: application/json' -d '[{"labels":{"alertname":"Test","severity":"critical"},"annotations":{"summary":"테스트"}}]'`. compose 는 `DISCORD_WEBHOOK_URL` 을 셸에서 export 하거나 `infra/.env` 에 두고 `docker compose -f infra/docker-compose.yml up -d alertmanager`

서비스의 trace·로그에는 `service.name`(= `spring.application.name`) 말고도 OTel 표준 resource 속성이 붙는다: `service.namespace`, `service.version`(이미지 태그), `deployment.environment.name`(`observability.environment`, 기본 `local`, EKS 는 `eks`), `service.instance.id`·`k8s.pod.name`·`k8s.node.name`·`k8s.namespace.name`. 파드 값은 Downward API 로 넣은 `OTEL_RESOURCE_ATTRIBUTES` 를 Spring Boot 가 읽는다(`templates/services.yaml`). replica 가 여럿이어도 어느 파드·노드의 trace·로그인지 보인다

## ArgoCD 로 올리기 (K-3, GitOps)

`deploy/argocd/root.yaml`(app-of-apps) 하나만 apply 하면 ArgoCD 가 Git 에서 나머지를 가져와 맞춘다.

| Application | 무엇 | wave |
|---|---|---|
| `commerce-saga-root` | `deploy/argocd/apps` 차트. 아래 셋과 OCI Helm 저장소 Secret 을 만든다 | |
| `strimzi` | Strimzi 오퍼레이터(OCI `quay.io/strimzi-helm`) | -2 |
| `envoy-gateway` | Envoy Gateway(OCI `docker.io/envoyproxy/gateway-helm`, Gateway API CRD 포함) | -2 |
| `commerce-saga` | `deploy/helm/commerce-saga` 를 같은 커밋에서, `edge.enabled=true` | 0 |

```bash
kind create cluster --name commerce-saga
REVISION=<push 된 커밋 또는 브랜치> scripts/k8s-argocd.sh   # 이미지 → kind, ArgoCD 설치, root apply, Synced·Healthy 대기
kubectl -n envoy-gateway-system port-forward svc/$(kubectl -n envoy-gateway-system get svc \
  -l gateway.envoyproxy.io/owning-gateway-name=commerce-edge -o jsonpath='{.items[0].metadata.name}') 80:80   # 80 은 관리자 권한(sudo)
# 화면: http://localhost (사용자), /admin/ (관리자), /auth/ (로그인). argocd/apps/values.yaml 이 경로 방식·이 주소로 둔다.
# 80 이 아닌 포트로 열면 로그인 뒤 SAS 가 돌려보내는 주소에서 포트가 빠진다(X-Forwarded-Proto 만 오면 Spring 이 기본 포트로 본다) → 80 으로 연다
```

## 결정 (K-3)

- **GitOps 는 app-of-apps**. 자식 Application 은 작은 Helm 차트(`argocd/apps`)라 `revision` 하나로 모든 자식이 같은 커밋을 본다(CI 는 PR 커밋을 넣는다). 자식 순서는 sync-wave(플랫폼 먼저), CRD 가 아직 없을 때는 `SkipDryRunOnMissingResource` + 재시도
- **edge = Gateway API(`Gateway`·`HTTPRoute`), 구현은 Envoy Gateway**. ingress-nginx 는 2026년에 은퇴해 Ingress 대신 k8s 표준 다음 API 를 쓴다. **화면 SPA·Spring Gateway(BFF)·SAS 의 정해진 경로만 연다**(설계 문서 7-1, ADR 0017). 서비스·DB·Kafka·Redis 는 내부망. 두 방식 `edge.mode`: `host`(화면마다 호스트 `edge.host.web·admin·auth`) / `path`(주소 하나 `edge.path.url` 에 `/`·`/admin/`·`/auth/`). 공개 주소로 SAS issuer·redirect URI·로그아웃 뒤 주소·쿠키 Secure 를 차트가 만든다(후속 5-3b)
- Envoy 의 Service 타입은 값(`edge.serviceType`). kind 는 LoadBalancer 가 없어 ClusterIP, 클라우드는 LoadBalancer
- 이미지는 여전히 `kind load`(레지스트리 없음). 레지스트리에 올리면 ArgoCD 만으로 끝난다
- CI `k8s` 워크플로의 `argocd` 잡이 Synced·Healthy, edge(경로 방식)로 화면·SAS·BFF 로그인 한 바퀴(`scripts/smoke-bff-login.sh`), 시나리오 0·1 을 확인한다

## EKS 에 올리기 (K-4, GHCR + ArgoCD GitOps)

클러스터는 이미 있다고 본다. CI 는 이미지만 GHCR 에 올리고, 클러스터 안의 ArgoCD 가 Git 을 보고 가져간다(CI 에 클러스터 권한 없음).

```
PR → CI(backend·e2e·k8s 등) → main 머지
  → release 워크플로: 마지막 배포 이후 main CI 가 통과할 때까지 대기(실패면 중단)
                     → 바뀐 이미지만 bootJar → GHCR push (amd64·arm64, 태그 = 이번 커밋 해시)
                     → release/eks 에 release 커밋 (그 main 커밋 + release-tags.yaml: 이미지별 태그)
  → EKS 의 ArgoCD: release/eks 가 움직이면 sync → 태그가 바뀐 Deployment 만 롤링 배포
```

| 파일 | 내용 |
|---|---|
| `.github/workflows/release.yml` | main push(문서만 바뀐 커밋 제외)·수동 실행(`all`: 전부 다시 빌드). `ghcr.io/klmin/commerce-saga/<이름>:<커밋 해시>` |
| `scripts/release-ci-gate.sh` | release 첫 잡. 마지막 배포 이후 main 커밋 하나하나의 CI 가 통과해야 넘어감, 실패·취소면 배포 중단(고친 뒤 수동 실행 `ci_from`) |
| `scripts/release-plan.sh` | release 가 부른다. 마지막 배포 커밋과 비교해 바뀐 이미지를 고르고 `release-tags.yaml` 을 만든다 ([docs/ci-cd.md](../docs/ci-cd.md) "배포") |
| `argocd/eks/root.yaml` | EKS 용 root. `release/eks` 를 보고 `values-eks.yaml` 을 값으로 쓴다. 이미지별 태그 `release-tags.yaml`(release/eks 에만 있음)은 apps 차트가 `.Files` 로 읽는다(root 는 바꾸지 않음) |
| `argocd/apps/values-eks.yaml` | GHCR, Envoy LoadBalancer(internet-facing), StorageClass, edge 경로 방식(공개 주소 `edge.path.url`) |
| `scripts/eks-argocd.sh` | 처음 한 번: ArgoCD 설치 → root apply → Synced·Healthy 대기 → 화면 주소 출력. `ARGOCD_DISCORD_WEBHOOK` 을 주면 sync 결과 Discord 알림도 켠다 |
| `argocd/notifications.yaml` | ArgoCD Notifications(배포 완료·sync 실패·Degraded → Discord) 값. 웹훅 URL 은 클러스터 Secret 에만 ([docs/ci-cd.md](../docs/ci-cd.md) "EKS 배포 결과") |

### 처음 한 번

1. main 에 머지(또는 Actions 에서 `release` 수동 실행) → GHCR 에 패키지 11개(서비스 9 + 화면 `web`·`admin` + connect, 옛 `frontend` 는 더 쓰지 않음), `release/eks` 브랜치가 생긴다
2. GHCR 패키지를 **public** 으로: GitHub → 프로필 → Packages → `commerce-saga/<이름>` → Package settings → Change visibility. 11개 모두 (private 이면 노드가 이미지를 못 받아 `ImagePullBackOff`)
3. 클러스터 준비 확인
   - 노드는 amd64(x86)·arm64(Graviton) 둘 다 된다. 이미지가 멀티 아키텍처라 노드가 자기 CPU 에 맞는 쪽을 받는다
   - **EBS CSI 드라이버** add-on 과 기본 StorageClass (`kubectl get sc` 에 `(default)`). 기본이 없으면 `values-eks.yaml` 의 `storageClass` 를 채운다
   - 노드 여유: Pod 약 26개(Argo CD 포함 약 31개). 요청 합계 CPU 약 2.3 코어, 메모리 약 8.5Gi, 실제 사용은 약 10Gi (서비스 9개 JVM + Kafka + Connect + Elasticsearch + 관측성). t3.large(8GiB, Pod 35개) 2대면 여유가 있다. t3.medium 은 Pod 수(17개)·메모리가 모자란다
4. `aws eks update-kubeconfig --name <클러스터> --region <리전>` 후 `scripts/eks-argocd.sh`
   - sync 결과를 Discord 로 받으려면: `read -rs ARGOCD_DISCORD_WEBHOOK && export ARGOCD_DISCORD_WEBHOOK && scripts/eks-argocd.sh` (이미 설치된 클러스터에 다시 돌려도 된다)
5. **공개 주소 넣기** (후속 5-3b): 로그인(SAS·BFF)은 브라우저가 보는 주소를 정확히 알아야 한다(issuer·redirect URI). 처음 sync 는 `edge.path.url` 이 비어 있어 화면은 뜨지만 로그인이 안 된다. Envoy 의 LB 주소를 확인해 `values-eks.yaml` 의 `edge.path.url` 에 넣고(예: `http://xxx.elb.amazonaws.com`) main 에 머지한다
   - LB 주소: `kubectl -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=commerce-edge` 의 EXTERNAL-IP
   - 화면: `<주소>/`(사용자), `<주소>/admin/`(관리자), 로그인은 `<주소>/auth/`. 도메인을 붙이면 `edge.mode: host` 와 `edge.host.web·admin·auth`

그 뒤로는 머지만 하면 된다. 바뀐 서비스만 새 이미지로 롤링된다. 롤백은 `git log --first-parent origin/release/eks` 에서 옛 release 커밋을 골라 `git push origin <옛 release 커밋>:refs/heads/release/eks --force` (그 커밋의 이미지가 GHCR 에 있다).

### 결정 (K-4)

- **레지스트리는 GHCR**: 저장소와 같은 곳, Actions 의 기본 `GITHUB_TOKEN` 으로 push. AWS IAM·OIDC 설정이 없다. public 패키지라 pull secret 도 없다
- **태그는 main 이 아니라 배포 브랜치에 커밋한다**: main 은 PR 로만 바뀌어(ruleset) 봇 커밋이 어렵다. 대신 release 워크플로가 이미지가 다 올라간 뒤에만 `release/eks` 에 release 커밋(main 커밋 + 이미지별 태그 `release-tags.yaml`)을 얹는다. Git 의 `release/eks` 가 곧 "지금 배포된 것"이다. 처음(K-4)에는 태그 = ArgoCD 가 sync 하는 커밋 해시 하나였는데, 서비스 하나만 바뀌어도 전부 롤링돼서 이미지별 태그로 바꿨다 (ADR 0014 "바뀐 서비스만 배포")
- **개발용 시드 사용자 없음**: `AUTH_FLYWAY_LOCATIONS=classpath:db/migration`. 공개된 admin 비밀번호로 누구나 관리자(결제 장애 주입)가 되지 않게. 사용자는 회원가입, 관리자 역할은 DB 에서 따로 준다
- **http 로 먼저**: TLS(도메인·ACM/cert-manager)를 붙이기 전이라 공개 주소가 http 다. 쿠키 Secure 는 차트가 공개 주소 스킴으로 정한다(https 면 켬)
- DB·Redis·Kafka 는 kind 와 같이 클러스터 안 단일 인스턴스(EBS PVC). 운영이면 RDS(logical replication)·ElastiCache·MSK 로 바꿀 자리
