# k8s 배포 (후속 3)

로컬 개발은 지금처럼 `infra/docker-compose.yml` + `java -jar` 로 한다. 이 디렉터리는 같은 구성을 k8s 에 올리는 방법이다.
클러스터를 상시 띄우지 않고, PR 마다 CI(`.github/workflows/k8s.yml`)가 kind 에 올려 스모크를 돈다.

| 경로 | 내용 |
|---|---|
| `docker/service.Dockerfile` | 백엔드 서비스 공용 이미지. bootJar 를 받아 Spring Boot 레이어로 풀어 실행(비 root) |
| `helm/commerce-saga/` | 차트 하나: Postgres(StatefulSet, 서비스별 DB·계정), Redis, Kafka(Strimzi `Kafka`·`KafkaNodePool`·`KafkaTopic`), 서비스 7개, 화면 |

## kind 에 올리기

필요: Docker, [kind](https://kind.sigs.k8s.io/), kubectl, Helm 4, JDK 25(Gradle 툴체인).

```bash
kind create cluster --name commerce-saga
scripts/k8s-deploy.sh            # 이미지 빌드 → kind 에 넣기 → Strimzi → 차트, 모두 준비될 때까지 기다림
scripts/k8s-port-forward.sh      # compose 때와 같은 로컬 포트 (8081~8086, gateway 8000, 화면 3500, Grafana 3030·Tempo 3210·Loki 3110·Prometheus 9091)
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
- **trace**: 클러스터에 Tempo 가 없어 `TRACING_SAMPLING_PROBABILITY=0`
- **auth-service 서명 키**: 주지 않으면 띄울 때마다 임시 키다(ADR 0008). 그래서 replicas 1 에 `Recreate` 전략(재배포 때 키가 다른 옛·새 Pod 가 함께 응답하지 않게). 여럿이면 키를 Secret 으로 넣는다
- **이미지**: kind 노드에 직접 넣는다(`kind load docker-image`). 레지스트리에 올리면 `image.registry` 만 채운다

## 결정 (K-2, Debezium)

- **Strimzi `KafkaConnect` 하나 + 서비스별 `KafkaConnector` 5개**(order·inventory·point·payment·auth). 커넥터 설정은 compose 의 `infra/connect/<서비스>-outbox.json` 과 같다(slot·publication 이름, Outbox Event Router, heartbeat). 서비스는 `OUTBOX_RELAY_ENABLED=false`
- **Connect 이미지** `docker/connect.Dockerfile`: Strimzi Kafka 이미지(1.3.1, Kafka 4.3.2) + Debezium Postgres 플러그인(compose 와 같은 버전·SHA-512). Strimzi 의 `build`(클러스터에서 빌드해 레지스트리에 올림)는 kind 에 레지스트리가 없어 쓰지 않는다
- **DB 비밀번호**: Connect 컨테이너가 각 서비스 DB Secret 을 env 로 받고, 커넥터는 `${env:<DB>_DB_PASSWORD}`(EnvVarConfigProvider)로 읽는다. 커넥터 CR 에 비밀번호를 쓰지 않는다
- **heartbeat 토픽**(`__debezium-heartbeat.<커넥터>`)은 브로커 자동 생성을 껐으므로 `KafkaTopic` 으로 만든다
- `debezium.enabled=false` 면 KafkaConnect 없이 서비스의 스케줄러 relay 로 돈다
- CI 확인: 커넥터 5개 Ready, 스모크 뒤 outbox 5개가 비어 있음(넣고 바로 지움), 활성 replication slot 5개

## 관측성 (Grafana·Tempo·Loki·Prometheus)

차트가 alloy·tempo·loki·prometheus·kafka-exporter·grafana 를 같이 올린다(`observability.enabled`, 기본 true, ADR 0014 "관측성"). 밖으로는 열지 않으니 `scripts/k8s-port-forward.sh` 로 연결해서 Grafana http://localhost:3030 → Explore(Tempo, span 의 Related logs → Loki) 또는 대시보드 commerce-saga 를 본다. 설정 파일은 `files/observability` 에 있는 `infra/` 의 사본이라, 바꿀 때는 `infra/` 를 고치고 복사한다(CI 가 diff 로 확인)

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
  -l gateway.envoyproxy.io/owning-gateway-name=commerce-edge -o jsonpath='{.items[0].metadata.name}') 18080:80
# 화면: http://localhost:18080
```

## 결정 (K-3)

- **GitOps 는 app-of-apps**. 자식 Application 은 작은 Helm 차트(`argocd/apps`)라 `revision` 하나로 모든 자식이 같은 커밋을 본다(CI 는 PR 커밋을 넣는다). 자식 순서는 sync-wave(플랫폼 먼저), CRD 가 아직 없을 때는 `SkipDryRunOnMissingResource` + 재시도
- **edge = Gateway API(`Gateway`·`HTTPRoute`), 구현은 Envoy Gateway**. ingress-nginx 는 2026년에 은퇴해 Ingress 대신 k8s 표준 다음 API 를 쓴다. **화면(Next)만 연다**: 브라우저는 Next 만 보고 Next 서버만 Spring Gateway 를 부른다(ADR 0011). Spring Gateway·서비스·DB·Kafka·Redis 는 내부망
- Envoy 의 Service 타입은 값(`edge.serviceType`). kind 는 LoadBalancer 가 없어 ClusterIP, 클라우드는 LoadBalancer
- 이미지는 여전히 `kind load`(레지스트리 없음). 레지스트리에 올리면 ArgoCD 만으로 끝난다
- CI `k8s` 워크플로의 `argocd` 잡이 Synced·Healthy, edge 로 화면, 시나리오 0·1 을 확인한다

## EKS 에 올리기 (K-4, GHCR + ArgoCD GitOps)

클러스터는 이미 있다고 본다. CI 는 이미지만 GHCR 에 올리고, 클러스터 안의 ArgoCD 가 Git 을 보고 가져간다(CI 에 클러스터 권한 없음).

```
PR → CI(backend·e2e·k8s 등) → main 머지
  → release 워크플로: bootJar → 이미지 9개 GHCR push (태그 = 커밋 해시) → release/eks 브랜치를 그 커밋으로
  → EKS 의 ArgoCD: release/eks 가 움직이면 sync, 이미지 태그 = 그 커밋 해시 → 롤링 배포
```

| 파일 | 내용 |
|---|---|
| `.github/workflows/release.yml` | main push(문서만 바뀐 커밋 제외)·수동 실행. `ghcr.io/klmin/commerce-saga/<이름>:<커밋 해시>` |
| `argocd/eks/root.yaml` | EKS 용 root. `release/eks` 를 보고 `$ARGOCD_APP_REVISION` 을 자식의 revision·이미지 태그로 넘긴다 |
| `argocd/apps/values-eks.yaml` | GHCR, Envoy LoadBalancer(internet-facing), StorageClass, http 임시 쿠키 설정 |
| `scripts/eks-argocd.sh` | 처음 한 번: ArgoCD 설치 → root apply → Synced·Healthy 대기 → 화면 주소 출력 |

### 처음 한 번

1. main 에 머지(또는 Actions 에서 `release` 수동 실행) → GHCR 에 패키지 9개, `release/eks` 브랜치가 생긴다
2. GHCR 패키지를 **public** 으로: GitHub → 프로필 → Packages → `commerce-saga/<이름>` → Package settings → Change visibility. 9개 모두 (private 이면 노드가 이미지를 못 받아 `ImagePullBackOff`)
3. 클러스터 준비 확인
   - 노드가 **amd64**(x86). Graviton(arm64)이면 release 워크플로를 멀티 아키텍처로 바꿔야 한다
   - **EBS CSI 드라이버** add-on 과 기본 StorageClass (`kubectl get sc` 에 `(default)`). 기본이 없으면 `values-eks.yaml` 의 `storageClass` 를 채운다
   - 노드 여유: 요청 합계 CPU 약 1.5 코어, 메모리 약 5Gi (서비스 7개 JVM + Kafka + Connect + Postgres)
4. `aws eks update-kubeconfig --name <클러스터> --region <리전>` 후 `scripts/eks-argocd.sh`

그 뒤로는 머지만 하면 된다. 롤백은 `git push origin <옛 커밋>:refs/heads/release/eks --force` (그 커밋의 이미지가 GHCR 에 있다).

### 결정 (K-4)

- **레지스트리는 GHCR**: 저장소와 같은 곳, Actions 의 기본 `GITHUB_TOKEN` 으로 push. AWS IAM·OIDC 설정이 없다. public 패키지라 pull secret 도 없다
- **태그를 Git 에 커밋하지 않는다**: main 은 PR 로만 바뀌어(ruleset) 봇 커밋이 어렵다. 대신 배포 브랜치 `release/eks` 를 이미지가 다 올라간 뒤에만 옮기고, ArgoCD 가 sync 하는 커밋 해시를 태그로 쓴다. Git 의 `release/eks` 가 곧 "지금 배포된 것"이다
- **개발용 시드 사용자 없음**: `AUTH_FLYWAY_LOCATIONS=classpath:db/migration`. 공개된 admin 비밀번호로 누구나 관리자(결제 장애 주입)가 되지 않게. 사용자는 회원가입, 관리자 역할은 DB 에서 따로 준다
- **http 로 먼저**: TLS(도메인·ACM/cert-manager)를 붙이기 전이라 `COOKIE_SECURE`·`AUTH_COOKIE_SECURE` 를 `false` 로 둔다. https 를 붙이면 지운다
- DB·Redis·Kafka 는 kind 와 같이 클러스터 안 단일 인스턴스(EBS PVC). 운영이면 RDS(logical replication)·ElastiCache·MSK 로 바꿀 자리
