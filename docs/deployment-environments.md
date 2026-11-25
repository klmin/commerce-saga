# 배포 환경 가이드: 로컬 → compose → k8s → ArgoCD(GitOps) → EKS

같은 commerce-saga 를 다섯 가지 방식으로 띄울 수 있다. 이 문서는 **각 방식에서 누가 무엇을 읽어서 어떻게 띄우는지**, 그리고 Helm·values·ArgoCD 가 서로 어떻게 이어지는지를 설명한다. 실행 명령과 결정 기록은 [deploy/README.md](../deploy/README.md) 를 본다.

## 한눈에 비교

| | 로컬 | compose | 자체 k8s (Helm 직접) | kind + ArgoCD | EKS + ArgoCD |
|---|---|---|---|---|---|
| 어디서 도나 | 내 PC (JVM) | 내 PC (Docker 컨테이너) | kind 클러스터 (내 PC) | kind 클러스터 (내 PC, CI) | AWS EKS |
| 누가 띄우나 | `./gradlew bootRun` | `docker compose up` | `scripts/k8s-deploy.sh` → `helm upgrade --install` | `scripts/k8s-argocd.sh` → ArgoCD | `scripts/eks-argocd.sh`(처음 한 번) → ArgoCD |
| 무엇을 읽나 | `application.yml` + 환경변수 | `infra/docker-compose.yml` + `infra/.env` | 차트 `deploy/helm/commerce-saga` + `values.yaml` | Git 의 `deploy/argocd/apps` → 앱 차트 | Git `release/eks` 의 `deploy/argocd/apps` + `values-eks.yaml` → 앱 차트 |
| 배포 방향 | - | - | **push** (사람·스크립트가 밀어 넣음) | **pull** (ArgoCD 가 Git 을 끌어옴) | **pull** |
| 이미지 | 없음 (jar 를 바로 실행) | 인프라는 공식 이미지 | 로컬 빌드 → `kind load` 로 노드에 직접 | 로컬 빌드 → `kind load` | GHCR `ghcr.io/klmin/commerce-saga/<이름>:<커밋 해시>` |
| GitOps | X | X | X | O (이미지만 예외) | O |
| 용도 | 개발 | 개발 (인프라) | k8s 매니페스트 검증, CI | GitOps 구성 검증, CI | 실제 배포 |

> 이 프로젝트에서 **로컬과 compose 는 같이 쓴다**: compose 로 인프라(Postgres·Kafka·Redis·관측성)를 띄우고, 서비스는 `bootRun` 으로 띄운다.

---

## 용어

| 용어 | 풀네임 / 뜻 |
|---|---|
| **k8s** | Kubernetes. 컨테이너를 여러 노드에 띄우고 관리하는 플랫폼(규격·소프트웨어 이름). YAML 매니페스트(Deployment, Service …)만 이해한다. 아래 kind·Docker Desktop·EKS 는 모두 "k8s 클러스터를 어디에 어떻게 만드느냐"의 차이일 뿐, 안에서 도는 k8s 는 같다 |
| **kind** | **K**ubernetes **IN** **D**ocker. 오픈소스 CLI 도구. Docker 컨테이너를 k8s 노드로 써서 내 PC(또는 CI 러너)에 k8s 클러스터를 만든다. `kind create cluster` 로 만들고 `kind delete cluster` 로 버린다. 레지스트리 없이 `kind load docker-image` 로 이미지를 노드에 바로 넣을 수 있다 |
| **Docker Desktop k8s** | Docker Desktop 설정에서 켜는 내장 k8s (kubectl 컨텍스트 `docker-desktop`). kind 와 비슷하게 내 PC 의 Docker 위에서 돌지만 **별개의 도구**다. 로컬 Docker 의 이미지를 그대로 볼 수 있어 `kind load` 가 필요 없다 |
| **직접 설치한 k8s** | VM·서버에 kubeadm, k3s 등으로 직접 설치·운영하는 클러스터. 이 프로젝트에서는 쓰지 않는다 |
| **EKS** | **E**lastic **K**ubernetes **S**ervice. AWS 가 컨트롤 플레인을 운영해 주는 관리형 k8s. 노드는 EC2, 디스크는 EBS, 로드밸런서는 ELB 를 쓴다 |
| **docker compose** | 여러 컨테이너를 YAML 하나(`docker-compose.yml`)로 띄우는 Docker 도구. k8s 가 아니다. Windows 에서는 Docker Desktop 이 Docker 엔진을 제공하고 그 위에서 돈다 |
| **Helm** | k8s 용 패키지 매니저 + 템플릿 엔진. 차트(템플릿)에 values(변수)를 넣어 매니페스트를 만들어 k8s 에 적용한다 |
| **ArgoCD** | k8s 안에서 도는 GitOps 도구. Git 을 주기적으로 보고, Git 에 적힌 상태와 클러스터가 다르면 맞춘다(sync) |
| **GitOps** | "배포될 상태를 Git 에 선언하고, 클러스터 안의 에이전트가 Git 을 끌어와 맞춘다"는 운영 방식. 배포 = Git 변경 |
| **GHCR** | **G**it**H**ub **C**ontainer **R**egistry. GitHub 의 이미지 저장소 (`ghcr.io`) |

### 두 축: "어디에(클러스터)" × "어떻게(배포 방식)"

k8s 쪽 환경 이름은 두 가지를 섞어 부른 것이다. 따로 떼어 보면 헷갈리지 않는다.

| 배포 방식 ↓ / 클러스터 → | kind | Docker Desktop k8s | EKS |
|---|---|---|---|
| Helm 직접 (사람이 push) | `scripts/k8s-deploy.sh` (CI `k8s` 잡) | 가능 (스크립트 없음) | 가능하지만 하지 않는다 |
| ArgoCD (Git 에서 pull) | `scripts/k8s-argocd.sh` (CI `argocd` 잡) | 가능 (`eks/root.yaml` 을 apply 하면 GHCR 이미지로 뜬다) | `scripts/eks-argocd.sh` |

- 위 표의 "자체 k8s" 는 **배포 방식**(Helm 직접)을 가리킨 말이고, 클러스터는 kind 다
- 클러스터가 달라도 차트·values·ArgoCD 설정은 같은 파일을 쓴다. 달라지는 것은 values 몇 개(이미지 레지스트리, LoadBalancer, StorageClass)뿐이다

### kind + ArgoCD 와 EKS + ArgoCD 는 무엇이 다른가

**ArgoCD 와 차트는 같고, 그 아래 클러스터와 이미지 공급 방식이 다르다.**

| | kind + ArgoCD | EKS + ArgoCD |
|---|---|---|
| 클러스터 | 내 PC 의 Docker 컨테이너 (단일 노드) | AWS 의 EC2 노드들 |
| root | `deploy/argocd/root.yaml` | `deploy/argocd/eks/root.yaml` |
| 보는 브랜치 | 스크립트가 넣은 `REVISION` (PR 커밋, 기본 `main`) | `release/eks` |
| 이미지 | 레지스트리 없음. 스크립트가 빌드 → `kind load` | release 워크플로가 GHCR 에 push |
| 이미지 태그 | 스크립트가 만든 `dev-<시각>` 을 root parameter 로 (모든 이미지 같은 태그) | 이미지마다 마지막으로 바뀐 main 커밋 해시 (`release/eks` 의 `release-tags.yaml`) |
| 외부 노출 | Envoy Service = ClusterIP (port-forward 로 접속) | Envoy Service = LoadBalancer (AWS ELB 주소) |
| 디스크 | kind 기본 StorageClass (노드 로컬 디스크) | EBS (EBS CSI 드라이버) |
| 진짜 GitOps 인가 | 매니페스트는 Git 에서 pull. 단 이미지는 스크립트가 넣어서 **반쯤** | 이미지·매니페스트 모두 Git 커밋 기준. **완전한** GitOps |
| 목적 | CI 에서 "ArgoCD 구성이 실제로 sync 되는가" 검증 | 실제 서비스 |

**자체 k8s(Helm 직접)** 은 같은 차트를 쓰지만 ArgoCD 없이 `helm upgrade --install` 로 밀어 넣는다. Git 을 바꿔도 아무 일도 안 일어나고, 스크립트를 다시 돌려야 바뀐다. 그래서 GitOps 가 아니다.

> kind 대신 Docker Desktop 의 내장 k8s(컨텍스트 `docker-desktop`)를 써도 "자체 k8s" 다. 이미지를 kind load 대신 로컬 Docker 에서 바로 쓰거나, EKS 처럼 GHCR 에서 받게 하면 된다.

---

## 1. 로컬 (bootRun)

```
./gradlew :services:order-service:bootRun
  └─ Spring Boot 가 src/main/resources/application.yml 을 읽음
       └─ ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092} 처럼 환경변수, 없으면 기본값(localhost)
```

- 인식 규칙: Spring Boot 가 `application.yml` 이라는 **정해진 이름**을 classpath 에서 찾는다
- 인프라(Postgres·Kafka·Redis·Elasticsearch)는 따로 있어야 한다 → compose 로 띄운다
- 포트: order 8081, inventory 8082, point 8083, payment 8084, notification 8085, auth 8086, product 8087, search 8089, gateway 8000

## 2. compose (인프라)

```
docker compose -f infra/docker-compose.yml up -d --wait
  └─ infra/docker-compose.yml 의 services: 를 컨테이너로
       postgres, redis, kafka, elasticsearch, connect(Debezium), kafka-ui,
       alloy, tempo, loki, prometheus, alertmanager, kafka-exporter, grafana
       (+ postgres-init, kafka-init: 초기화하고 끝나는 컨테이너)
```

- 인식 규칙: `-f` 로 파일을 지정한다(지정하지 않으면 현재 폴더의 `docker-compose.yml`/`compose.yaml`). 같은 폴더의 `.env` 를 변수로 읽는다
- `environment:` 에 적은 값이 **그대로** 컨테이너 환경변수가 된다. 한 단계짜리 매핑
- 컨테이너끼리는 서비스 이름(`kafka`, `postgres`)으로 찾는다. 내 PC 에서는 `localhost:<포트>`

## 3. 자체 k8s (Helm 직접, 수동 배포)

```
kind create cluster --name commerce-saga
scripts/k8s-deploy.sh
  ├─ ./gradlew bootJar → docker build (백엔드 서비스 9개 + frontend + connect)
  ├─ kind load docker-image commerce-saga/<이름>:dev-<시각>   ← 노드에 이미지 직접
  ├─ helm upgrade --install strimzi oci://quay.io/strimzi-helm/...   ← Kafka 오퍼레이터
  └─ helm upgrade --install commerce deploy/helm/commerce-saga --set image.tag=dev-<시각>
       └─ Helm 이 차트를 렌더링 → 매니페스트를 k8s API 에 적용 → k8s 가 Pod 생성
```

여기서 처음으로 **Helm 차트**가 나온다. 아래 [Helm 차트 구조](#helm-차트-구조) 참고.

## 4. kind + ArgoCD

```
kind create cluster --name commerce-saga
REVISION=<push 된 커밋> scripts/k8s-argocd.sh
  ├─ 이미지 빌드 → kind load            (k8s-deploy.sh 의 앞부분만)
  ├─ helm install argo-cd               (ArgoCD 설치)
  └─ deploy/argocd/root.yaml 의 revision·imageTag 를 바꿔서 kubectl apply
       └─ 이후는 ArgoCD 가 Git 에서 (아래 "ArgoCD 흐름"과 같음)
```

## 5. EKS + ArgoCD (GitOps)

```
[처음 한 번]
aws eks update-kubeconfig --name <클러스터> --region <리전>
scripts/eks-argocd.sh
  ├─ helm install argo-cd
  └─ kubectl apply -f deploy/argocd/eks/root.yaml

[그 뒤로는 매번]
PR 머지 → main
  → .github/workflows/release.yml
       마지막 배포 이후 바뀐 이미지만 bootJar → ghcr.io/klmin/commerce-saga/<이름>:<커밋 해시> push (amd64·arm64)
       → release/eks 에 release 커밋 (그 main 커밋 + release-tags.yaml: 이미지별 태그)
  → EKS 안의 ArgoCD 가 release/eks 변경을 감지 → sync → 태그가 바뀐 Deployment 만 롤링
```

- CI 에는 클러스터 권한이 없다. CI 는 이미지 push 와 브랜치 이동까지만 하고, 배포는 클러스터 안의 ArgoCD 가 끌어와서 한다(pull)
- `release/eks` 브랜치의 끝 커밋 = 지금 배포된 버전. 롤백은 브랜치를 옛 release 커밋으로 옮기면 된다 (`git log --first-parent origin/release/eks`)
- 어떤 이미지를 다시 빌드하는지는 [docs/ci-cd.md](ci-cd.md) "배포"
- 클러스터 준비 조건(EBS CSI 드라이버와 기본 StorageClass, 노드 여유, GHCR 패키지 public)은 [deploy/README.md](../deploy/README.md#eks-에-올리기-k-4-ghcr--argocd-gitops)

---

## Helm 차트 구조

**k8s 는 Helm 차트를 읽지 못한다.** k8s 가 아는 건 매니페스트(Deployment, Service …)뿐이다. Helm(또는 ArgoCD 안의 Helm)이 차트를 매니페스트로 **렌더링**해서 k8s 에 넣는다.

Helm 을 yum 에 비유하면 이렇다.

| yum | Helm |
|---|---|
| rpm 패키지 | 차트 |
| 설정 파일 | values |
| 설치된 패키지 | release (이 프로젝트는 `commerce`) |
| `yum install`/`update` | `helm install`/`upgrade` (EKS 에서는 ArgoCD 가 대신) |

다른 점은 Helm 이 **템플릿 엔진**이라는 것이다. 차트 안의 YAML 에 빈칸(`{{ }}`)이 있고 values 가 그 빈칸을 채운다.

### 이름 규칙 (Helm 이 정한 것)

```
deploy/helm/commerce-saga/   ← 폴더 이름은 자유
├─ Chart.yaml                ← 이름 고정. 있으면 "여기가 차트". 차트 이름·버전
├─ values.yaml               ← 이름 고정. 기본 변수. 자동으로 읽는다
├─ templates/                ← 이름 고정. 안의 파일을 전부 렌더링한다 (파일 이름은 자유)
│   ├─ _helpers.tpl          ← _ 로 시작: 렌더링 결과를 k8s 로 보내지 않는다. 공용 함수(define)만 둔다
│   ├─ services.yaml         ← 백엔드 서비스 (values 의 services: 목록을 range 로 돌며 하나씩)
│   ├─ frontend.yaml         ← 화면(Next)
│   ├─ postgres.yaml         ← Postgres StatefulSet, 서비스별 DB 초기화
│   ├─ redis.yaml
│   ├─ elasticsearch.yaml    ← 상품 검색용 Elasticsearch
│   ├─ auth-jwt-key.yaml     ← auth-service 서명 키·OAuth 클라이언트 시크릿 Secret 을 한 번 만드는 hook Job
│   ├─ kafka.yaml            ← Strimzi Kafka·KafkaNodePool·KafkaTopic CR (브로커 Pod 는 Strimzi 가 띄움)
│   ├─ debezium.yaml         ← Strimzi KafkaConnect·KafkaConnector (outbox relay)
│   ├─ observability.yaml    ← Alloy·Tempo·Loki·Prometheus·Alertmanager·Grafana
│   └─ edge.yaml             ← Gateway API Gateway·HTTPRoute (외부 진입점, Envoy Gateway 가 구현)
└─ files/                    ← 이름 자유. 템플릿이 .Files.Get 으로 읽는 SQL·설정 (infra/ 의 사본)
```

- **스캔 방식**: Helm 은 지정받은 차트 폴더 하나에서 `Chart.yaml`·`values.yaml`·`templates/` 를 정해진 이름으로 찾는다. 하위 폴더를 뒤져서 차트를 찾지는 않는다
- **차트 폴더는 누가 지정하나**: Helm 직접이면 `helm upgrade --install commerce deploy/helm/commerce-saga` 의 인자. ArgoCD 면 Application 의 `spec.source.path`
- **ArgoCD 의 인식**: `path` 에 `Chart.yaml` 이 있으면 Helm 차트로 보고 Helm 으로 렌더링한다 (없으면 일반 YAML 폴더로 본다)

### `_helpers.tpl` 의 "출력하지 않는다"

`services.yaml` 을 렌더링하면 Deployment·Service·Secret 이 나오고 그게 k8s 에 들어간다(= 출력). `_helpers.tpl` 은 렌더링해도 k8s 로 보낼 결과가 없다. 대신 함수를 정의해 두고 다른 템플릿이 불러 쓴다.

```yaml
# _helpers.tpl
{{- define "commerce.image" -}}            # 함수 정의
  registry/repositoryPrefix/이름:tag 를 조립
{{- end -}}

# services.yaml
image: {{ include "commerce.image" (dict "name" $name "root" $root) }}   # 호출
```

### values 는 무엇인가 (compose 의 environment 와 차이)

- compose: `environment:` 값 → 컨테이너 환경변수. **한 단계**
- Helm: values → **템플릿에 넣는 변수**. 그 값을 어디에 쓸지는 템플릿이 정한다. **두 단계**
  - `services.auth-service.env.AUTH_COOKIE_SECURE` → 템플릿이 Pod 의 `env:` 로 옮긴다 → 결과적으로 compose 와 같음
  - `image.registry` → 환경변수가 아니라 이미지 주소 조립에 쓰인다
  - `storageClass` → PVC 의 `storageClassName` 에 쓰인다
  - `edge.serviceType` → Envoy Service 의 `type` 에 쓰인다

### values 덮어쓰기 순서

뒤에 오는 것이 이긴다.

```
차트의 values.yaml (자동)
  → valueFiles 로 지정한 파일 (예: values-eks.yaml)
    → parameters / --set (예: imageTag)
```

- `values.yaml` 만 자동으로 읽는다. 다른 이름의 values 파일은 반드시 지정해야 읽는다 (`helm -f values-eks.yaml`, ArgoCD 는 `helm.valueFiles`)

---

## ArgoCD 흐름 (app-of-apps)

### root Application

`root.yaml` 은 Helm 규격이 아니라 **ArgoCD 의 `Application` 리소스 하나**다. 파일 이름은 임의로 정한 것이고, ArgoCD 가 보는 건 내용이다.

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application              # ArgoCD 가 설치한 CRD
metadata:
  name: commerce-saga-root     # 이름 자유
  namespace: argocd            # ArgoCD 가 설치된 namespace 여야 한다
spec:
  source:
    repoURL: https://github.com/klmin/commerce-saga.git   # 어느 저장소
    targetRevision: release/eks                            # 어느 브랜치(커밋·태그도 가능)
    path: deploy/argocd/apps                               # 어느 폴더 (Chart.yaml 있음 → Helm)
    helm:
      valueFiles: [values-eks.yaml]                        # values.yaml 위에 덮을 파일
      parameters:                                          # 그 위에 덮을 값
        - { name: revision, value: $ARGOCD_APP_REVISION }
        - { name: imageTag, value: $ARGOCD_APP_REVISION }  # release-tags.yaml 에 없는 이미지의 태그 (예전 방식 커밋용)
  destination:
    server: https://kubernetes.default.svc                 # 어느 클러스터 (자기 자신)
  syncPolicy:
    automated: { prune: true, selfHeal: true }             # 자동 sync, Git 에서 지운 건 지우고, 손으로 바꾼 건 되돌림
```

- root.yaml 은 **ArgoCD 가 Git 에서 읽는 파일이 아니다**. `kubectl apply` 로 클러스터에 등록하면 ArgoCD 가 그 Application 객체를 읽는다. 그래서 root.yaml 을 Git 에서 고치면 다시 apply 해야 반영된다
- ArgoCD 가 Git 에서 계속 보는 것은 root 가 가리키는 `repoURL` + `targetRevision` + `path`

### `$ARGOCD_APP_REVISION`

ArgoCD 버전이 아니다. **ArgoCD 가 지금 sync 하는 Git 커밋 해시**를 ArgoCD 가 렌더링할 때 자동으로 넣어 주는 내장 변수다. 파일 어디에도 값이 없다.

- `revision` 으로 → 자식 Application 도 브랜치 이름이 아니라 **같은 커밋**을 본다. sync 중에 브랜치가 움직여도 root 와 자식이 어긋나지 않는다
- `imageTag` 로 → 공통 이미지 태그(기본값). 지금 EKS 는 이미지별 태그를 `release-tags.yaml` 이 모두 주므로 쓰이지 않고, 그 파일이 없는 예전 release 커밋으로 되돌렸을 때만 쓰인다
- `release/eks` 의 끝은 release 커밋(main 커밋 + `release-tags.yaml`)이라 `$ARGOCD_APP_REVISION` 은 그 release 커밋 해시다. 이미지 태그는 main 커밋 해시라 서로 다르다

### `deploy/argocd/apps` 차트 (Application 을 만드는 차트)

이것도 Helm 차트다. 다만 렌더링 결과가 Pod 가 아니라 **ArgoCD Application 들**이다.

| 파일 | 역할 |
|---|---|
| `Chart.yaml` | 이 폴더가 Helm 차트라는 표시(이름 `commerce-saga-apps`, 버전). 내용은 간단해도 이게 있어야 ArgoCD 가 Helm 으로 렌더링한다 |
| `values.yaml` | 기본 변수. `repoURL`·`revision`(자식이 볼 저장소·커밋), `imageTag`, `namespace`, Strimzi·Envoy Gateway 버전, `commerceValues`(앱 차트로 넘길 값) |
| `values-eks.yaml` | EKS 에서만 덮을 값. `commerceValues` 안에 GHCR 주소, LoadBalancer, StorageClass, 쿠키·시드 사용자 설정 |
| `templates/repositories.yaml` | 외부 Helm 저장소(quay.io/strimzi-helm, docker.io/envoyproxy)를 ArgoCD 에 등록하는 Secret. 이게 있어야 아래 두 차트를 받는다 (wave -3) |
| `templates/platform.yaml` | Application **strimzi**(Kafka 오퍼레이터), **envoy-gateway**(Gateway API 구현). 외부 차트를 설치 (wave -2) |
| `templates/commerce-saga.yaml` | Application **commerce-saga**. 같은 저장소·같은 커밋의 `deploy/helm/commerce-saga` 를 설치. `commerceValues` + `image.tag` 를 값으로 넘김 (wave 0) |

`templates/` 에 있는 건 Pod 이미지가 아니라 **"무엇을 설치할지" 적은 목록**이다. 모니터링·Kafka·DB 같은 실제 리소스는 앱 차트(`deploy/helm/commerce-saga/templates/`)에 있다.

`sync-wave` 는 순서다. 숫자가 작은 것부터 적용하고 Healthy 가 될 때까지 기다린다. Kafka CR 을 만들려면 Strimzi CRD 가 먼저 있어야 하므로 오퍼레이터가 앞선다.

### 전체 매핑 (EKS)

```
[사람, 한 번] kubectl apply deploy/argocd/eks/root.yaml
      │
      ▼
Application commerce-saga-root
  Git release/eks @ <커밋> : deploy/argocd/apps        ← Chart.yaml → Helm
  values.yaml ← values-eks.yaml ← revision·imageTag = <커밋>
      │ 렌더링
      ├─ Secret repo-strimzi, repo-envoyproxy          (wave -3)
      ├─ Application strimzi                           (wave -2) → quay.io 차트 → Strimzi 오퍼레이터 Pod
      ├─ Application envoy-gateway                     (wave -2) → docker.io 차트 → Envoy Gateway 컨트롤러 Pod
      └─ Application commerce-saga                     (wave 0)
            Git 같은 <커밋> : deploy/helm/commerce-saga  ← Chart.yaml → Helm
            values.yaml ← commerceValues(GHCR, LB …) ← image.tag = <커밋>
                │ 렌더링 (templates/*.yaml 전부, _helpers.tpl 제외)
                ├─ Deployment 백엔드 서비스 + frontend   → Pod, 이미지 ghcr.io/klmin/commerce-saga/<이름>:<커밋>
                ├─ StatefulSet postgres, Deployment redis
                ├─ Kafka·KafkaTopic·KafkaConnect CR  → Strimzi 오퍼레이터가 브로커·Connect Pod 생성
                ├─ 관측성 Deployment들
                └─ Gateway·HTTPRoute                 → Envoy Gateway 가 Envoy Pod + LoadBalancer Service 생성 → AWS ELB
```

### 이미지 주소 한 줄 추적

```
helm/commerce-saga/values.yaml   image.registry: ""   repositoryPrefix: commerce-saga   tag: dev
argocd/apps/values-eks.yaml      commerceValues.image.registry: ghcr.io/klmin
argocd/apps/release-tags.yaml   commerceValues.image.tags.order-service: 59f17c1...  (release/eks 에만, apps 차트가 .Files 로 읽음)
argocd/eks/root.yaml             imageTag = $ARGOCD_APP_REVISION (tags 에 없을 때만)
argocd/apps/templates/commerce-saga.yaml   image.tag ← imageTag 를 commerceValues 에 합쳐 넘김
helm/commerce-saga/templates/_helpers.tpl  commerce.imageTag = image.tags.<이름> 있으면 그것, 없으면 image.tag
                                           commerce.image = registry/repositoryPrefix/이름:imageTag
helm/commerce-saga/templates/services.yaml image: {{ include "commerce.image" ... }}
                                 ▼
k8s Deployment                   image: ghcr.io/klmin/commerce-saga/order-service:59f17c1...
```

---

## 자주 헷갈리는 것

### "렌더링" 이란

템플릿의 빈칸(`{{ ... }}`)을 values 로 채워 **순수한 k8s YAML** 을 만드는 일이다. 자바의 템플릿 엔진(Thymeleaf 등)이 HTML 을 만드는 것과 같다.

```yaml
# 렌더링 전 (templates/services.yaml)          # 렌더링 후 (k8s 가 받는 것)
replicas: {{ $svc.replicas | default 1 }}       replicas: 1
image: {{ include "commerce.image" ... }}       image: ghcr.io/klmin/commerce-saga/order-service:59f17c1...
```

"Chart.yaml 있음 → Helm 으로 렌더링" 은: ArgoCD 가 `path` 폴더를 열었을 때 `Chart.yaml` 이 있으면 "이건 Helm 차트다" 라고 판단해 `helm template` 과 같은 렌더링을 거친 결과를 k8s 에 적용한다는 뜻이다. `Chart.yaml` 이 없으면 폴더 안 YAML 을 그대로 적용한다.

### Chart.yaml · values.yaml · templates/ 는 각각 무엇인가

| | 무엇 | compose 에 비유하면 | k8s 가 보나 |
|---|---|---|---|
| `Chart.yaml` | 차트 표시 + 이름·버전(+의존 차트). **이 파일이 있어야 폴더가 차트로 인식된다**. 내용이 정보뿐인 게 정상이다 | (없음. 패키지의 이름표) | X |
| `values.yaml` | **템플릿에 넣을 변수**의 기본값 | `.env` | X |
| `templates/*.yaml` | **k8s 리소스의 틀**. 렌더링되면 그대로 k8s 에 들어간다 | `docker-compose.yml` 의 `services:` | 렌더링 결과를 본다 |

- **values 는 Helm 변수다. k8s 변수가 아니다.** k8s 는 values.yaml 을 전혀 모른다. Helm 이 렌더링할 때만 쓰고 버린다
- 다만 템플릿이 어떤 value 를 Pod 의 `env:` 에 옮겨 적으면, **그 결과로** 컨테이너 안에서 환경변수로 보인다. 예) `services.auth-service.env.AUTH_COOKIE_SECURE` → Pod env → Spring 의 `application.yml` 이 읽음. values 가 직접 k8s 로 가는 게 아니라 템플릿을 거쳐서 간다
- 반대로 `image.registry`, `storageClass`, `edge.serviceType` 같은 value 는 환경변수가 되지 않고 이미지 주소·PVC·Service 타입 같은 리소스 정의에 쓰인다
- "전역변수" 라기보다 **차트 하나의 설정값**이다. 차트마다 자기 values 를 따로 가진다 (`argocd/apps` 의 values 와 `helm/commerce-saga` 의 values 는 서로 다르다. `commerceValues` 로 넘겨 줘야만 전달된다)

### 두 차트의 templates/ 는 성격이 다르다

| 차트 | templates/ 안에 있는 것 | 렌더링 결과 | 비유 |
|---|---|---|---|
| `deploy/argocd/apps` | `commerce-saga.yaml`, `platform.yaml`, `repositories.yaml` | ArgoCD **Application**(= "이 차트를 설치하라" 는 지시서)과 저장소 Secret | 설치 목록 |
| `deploy/helm/commerce-saga` | `services.yaml`, `kafka.yaml`, `postgres.yaml` … | **Deployment·StatefulSet·Service·Kafka CR** 등 실제 리소스 → Pod | compose 의 `services:` |

- **우리 이미지(백엔드 서비스 + 화면)는 `deploy/helm/commerce-saga/templates/services.yaml`·`frontend.yaml` 에 정의되어 있다.** `argocd/apps/templates/commerce-saga.yaml` 에는 이미지가 없고, "저 차트를 설치하라" 는 경로(`path: deploy/helm/commerce-saga`)와 넘겨 줄 값만 있다
- ArgoCD 는 commerce-saga Application 의 `path` 로 가서 → `Chart.yaml` 을 보고 Helm 차트로 인식 → 그 차트의 `templates/` 를 **다시** 전부 렌더링한다. 그래서 차트를 두 번 거친다 (apps 차트 → Application, 앱 차트 → Pod)

### "커밋 해시" 란

Git 커밋마다 붙는 고유 ID 다 (예: `59f17c1ce2d05de957cf5dfcc1313e0e04a13937`, 줄여서 `59f17c1`). `git log` 에 보이는 그 값이다. k8s 에 무언가를 커밋하는 게 아니다.

이 값을 **이미지 태그 문자열로 재사용**할 뿐이다.

1. main 커밋 `59f17c1` 에서 order-service 만 바뀌었다. release 워크플로가 order-service 만 빌드해 `order-service:59f17c1...` 로 GHCR 에 push 한다
2. `release-tags.yaml` 에 `order-service: 59f17c1...` 을 쓰고, 나머지 이미지는 이전 태그 그대로 둔다. 이 파일을 담은 release 커밋을 `release/eks` 에 얹는다
3. ArgoCD 가 그 release 커밋을 sync 하면서 `release-tags.yaml` 을 values 로 읽는다
4. order-service Deployment 의 `image:` 만 `...:59f17c1...` 로 바뀌어 그것만 롤링된다. 다른 Deployment 는 그대로다

그래서 "지금 떠 있는 이미지마다 = 그 이미지가 마지막으로 바뀐 main 커밋" 이 맞는다. `release-tags.yaml` 이 그 목록이다.

### 이미지는 누가, 어떻게 받나

values → 템플릿 → Deployment 의 `image:` 문자열까지가 Helm·ArgoCD 의 일이다. **실제로 이미지를 내려받는 것은 k8s 노드의 kubelet** 이다.

```
values-eks.yaml    commerceValues.image.registry = ghcr.io/klmin
release-tags.yaml  commerceValues.image.tags.<이름> = <그 이미지가 마지막으로 바뀐 커밋 해시>
      │  apps 차트 commerce-saga.yaml 이 합쳐 앱 차트의 values 로 넘김 (image.registry, image.tags, image.tag)
      ▼
앱 차트 _helpers.tpl   registry / repositoryPrefix / 이름 : (image.tags.<이름> 또는 image.tag)
      ▼
Deployment  image: ghcr.io/klmin/commerce-saga/order-service:<커밋 해시>
      ▼  k8s 가 Pod 를 노드에 배치
노드의 kubelet → ghcr.io 에서 pull (패키지가 public 이라 인증 없음) → 컨테이너 실행
```

kind 는 레지스트리 주소가 비어 있어 `image: commerce-saga/order-service:dev-…` 가 되고, `kind load` 로 노드에 미리 넣어 둔 이미지를 쓴다(`pullPolicy: IfNotPresent` 라 받으러 가지 않는다).

---

## 무엇이 GitOps 인가

다음 세 가지를 만족하면 GitOps 다.

1. 배포될 상태(차트, values, 이미지 태그)가 **Git 에 선언**되어 있다
2. 클러스터 안의 에이전트(ArgoCD)가 Git 을 **끌어와서(pull)** 맞춘다. CI 가 `kubectl apply`/`helm install` 로 밀어 넣지 않는다
3. 바꾸려면 **Git 을 바꾼다**. 클러스터를 손으로 고치면 `selfHeal` 이 Git 상태로 되돌린다

| | 1 Git 선언 | 2 pull | 3 Git 으로만 변경 |
|---|---|---|---|
| 자체 k8s (Helm 직접) | O (차트는 Git) | X (스크립트가 push) | X |
| kind + ArgoCD | 매니페스트 O, 이미지 X (스크립트가 kind load) | O | 매니페스트만 |
| EKS + ArgoCD | O (이미지별 태그 = `release/eks` 의 `release-tags.yaml`) | O | O |

GitOps 바깥에 남는 것:

- **부트스트랩**: ArgoCD 설치와 root apply 는 처음 한 번 손으로 한다(`scripts/eks-argocd.sh`). 어느 GitOps 구성이든 보통 이렇다
- **클러스터 자체**: EKS 생성, EBS CSI 애드온, 노드그룹은 Git 밖(eksctl·콘솔·Terraform)
- **비밀값**: 지금 DB 비밀번호 등은 차트 안의 로컬 개발용 값이다. 운영 수준이라면 Sealed Secrets 나 External Secrets(AWS Secrets Manager)로 Git 에 평문 없이 관리해야 한다

## 상태 보는 곳

| 보고 싶은 것 | 어디서 |
|---|---|
| 무엇을 배포하라고 했나 (원하는 상태) | GitHub `release/eks` 브랜치, Actions 의 `release` 워크플로 |
| 이미지가 올라갔나 | GitHub Packages (GHCR) |
| 클러스터가 따라갔나 (실제 상태) | ArgoCD UI 또는 `kubectl -n argocd get applications` (Synced·Healthy, sync 된 커밋, 리소스 트리) |

ArgoCD UI 는 밖으로 열지 않는다. `kubectl -n argocd port-forward svc/argocd-server 8443:80` 후 http://localhost:8443, 사용자 `admin`, 비밀번호는 `argocd-initial-admin-secret` Secret.
