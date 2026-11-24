# ADR 0014. k8s 배포: Helm 차트 하나, Kafka 는 Strimzi, relay 는 Strimzi KafkaConnect 의 Debezium, GitOps 는 ArgoCD, edge 는 Gateway API, 검증은 CI 의 kind

- 상태: 채택 (후속 3 K-1·K-2·K-3)
- 날짜: 2026-11-24

## 배경

1~3단계와 후속 1·2 는 docker-compose + `java -jar` 로 돌렸다. 후속 3 은 같은 구성을 k8s 에 올린다. 브리프 5절이 정한 범위는 "Helm, Strimzi, ArgoCD, 클러스터를 상시 띄우지 않고 CI 에서 kind 로 검증"이다. 정할 것은 다음과 같다.

- 이미지: 서비스 7개를 어떻게 이미지로 만드나
- 차트: 서비스마다 차트인가, 하나인가. Postgres·Redis 는 어떻게 두나
- Kafka·Debezium: Strimzi 로 무엇을 만드나, 버전은
- 검증: 어디서 무엇을 확인하나

## 결정

### 이미지: 공용 Dockerfile 하나, 빌드는 이미지 밖에서

- `deploy/docker/service.Dockerfile` 이 Gradle 이 만든 bootJar 를 받아 Spring Boot 레이어(의존성·로더·애플리케이션)로 풀어 실행한다. 비 root, `MaxRAMPercentage=75`
- 이미지 안에서 Gradle 을 돌리지 않는다. 서비스 7개마다 의존성을 다시 받게 되고, CI 는 이미 bootJar 를 만든다
- Paketo(`bootBuildImage`)·Jib 는 쓰지 않는다. 새 도구 없이 Dockerfile 하나로 7개가 같고, compose 의 `java -jar` 와 같은 JAR 를 쓴다

### 차트: `deploy/helm/commerce-saga` 하나

- 서비스 7개 + 화면 + Postgres·Redis·Kafka 가 한 릴리스다. 서비스마다 차트를 두면 값(포트·DB·Kafka 주소)이 일곱 군데로 흩어진다. 서비스는 `values.services` 의 한 항목(포트, DB, outbox 여부, 추가 env)이고 템플릿 하나가 Deployment·Service·DB Secret 을 만든다
- 설정은 **각 서비스 application.yml 의 환경변수를 채우기만** 한다. 서비스 코드·설정은 그대로다
- 서비스 경계: 서비스마다 자기 DB 계정 Secret 만 받는다(다른 서비스 DB 비밀번호를 갖지 않음). 비밀번호는 모두 로컬 개발용 값
- **Postgres·Redis 는 오퍼레이터 없이** 차트 안의 StatefulSet·Deployment 로 둔다(compose 와 같은 단일 인스턴스). 새 오퍼레이터는 Strimzi 하나로 끝낸다. 운영이면 관리형 DB·오퍼레이터(CloudNativePG 등)로 바꿀 자리다
- Postgres 초기화 SQL 은 `infra/postgres/init` 의 사본을 차트에 둔다(Helm 은 차트 밖 파일을 못 읽는다). CI 가 두 파일이 같은지 검사한다
- 클러스터에 Tempo 가 없어 trace 를 내보내지 않는다(`TRACING_SAMPLING_PROBABILITY=0`)
- auth-service 는 서명 키를 주지 않으면 임시 키라(ADR 0008) replicas 1 에 `Recreate` 전략이다. RollingUpdate 면 재배포 중 키가 다른 옛·새 Pod 가 함께 응답해, 방금 받은 토큰이 다른 Pod 의 JWKS 로 검증돼 401 이 될 수 있다. 여럿이면 키를 Secret 으로 넣는다

### Kafka: Strimzi `Kafka` + `KafkaNodePool`(KRaft) + `KafkaTopic`, 버전 4.3.2

- 노드 하나가 broker·controller 를 겸한다(kind·CI). 토픽은 compose 처럼 `KafkaTopic` 으로 명시적으로 만들고 브로커 자동 생성을 끈다(DLT 포함 16개 + heartbeat 5개)
- **Kafka 4.3.2**: 최신 Strimzi 1.3.1(2026-11-04)이 지원하는 마지막 버전이다. 서비스의 클라이언트는 4.4.0(compose 브로커와 같음)이고 4.3 브로커와 호환된다. Strimzi 가 4.4 를 지원하면 맞춘다

### relay: Strimzi `KafkaConnect` + 서비스별 `KafkaConnector` (ADR 0004 의 배포 기준)

- Connect 이미지(`deploy/docker/connect.Dockerfile`)는 Strimzi Kafka 이미지에 Debezium Postgres 플러그인(compose 와 같은 버전·SHA-512)을 얹는다. Strimzi 의 `build`(클러스터에서 빌드해 레지스트리에 올림)는 kind 에 레지스트리가 없어 쓰지 않는다
- 커넥터 설정은 compose 의 `infra/connect/<서비스>-outbox.json` 과 같다(slot·publication 이름, Outbox Event Router, heartbeat). 커넥터를 REST 가 아니라 CR 로 관리한다(`strimzi.io/use-connector-resources`)
- DB 비밀번호는 Connect 컨테이너가 서비스 DB Secret 을 env 로 받고, 커넥터는 `${env:...}`(EnvVarConfigProvider)로 읽는다. CR 에 비밀번호를 쓰지 않는다
- 서비스는 `OUTBOX_RELAY_ENABLED=false`. `debezium.enabled=false` 면 스케줄러 relay 로 돈다

### 검증: PR 마다 CI 의 kind

- `.github/workflows/k8s.yml`: kind → 이미지 빌드·`kind load` → Strimzi(Helm OCI) → 차트 → Kafka·토픽·Deployment·커넥터 Ready 까지 기다림 → `kubectl port-forward` 로 compose 때와 같은 로컬 포트에 연결 → 기존 스모크(시나리오 0·1·9·10)를 그대로 → Debezium 이 발행했는지(커넥터 5개 Ready, outbox 비어 있음, 활성 slot 5개)
- 스크립트(`scripts/k8s-deploy.sh`, `k8s-port-forward.sh`)는 로컬 kind 에서도 같다
- 장애 시나리오(서비스 다운, 커넥터 중단 등)는 compose 기준 e2e 워크플로가 계속 증명한다. k8s 워크플로는 "같은 시스템이 k8s 에서도 같은 결과"까지

## 결과

- 좋아진 것: 서비스 코드 변경 없이 같은 JAR·같은 커넥터 설정으로 k8s 에 올라간다. PR 마다 kind 에서 Saga·보상·소유권·멱등 주문과 Debezium 발행까지 확인된다
- 감수한 것: Postgres·Redis 는 단일 인스턴스(운영 수준 아님). Kafka 브로커 버전이 compose(4.4.0)와 다르다(4.3.2). 커넥터 설정이 compose json 과 차트 두 곳에 있다(바꿀 때 같이 바꾼다). 클러스터 안 trace 없음

## K-2 추가 결정: slot 준비 전 outbox 쓰기를 막는다 (ADR 0004 위험 4 해결)

- 문제: Debezium 모드의 `OutboxWriter` 는 행을 넣고 바로 지운다. replication slot·publication 이 생기기 전에 커밋한 메시지는 WAL 에서 읽을 곳도, 테이블에도 없어 **영영 발행되지 않는다**. compose 는 배포 순서(서비스 → 커넥터 → 트래픽)로 피했지만, k8s 에서는 Pod 가 뜨자마자 Kafka 를 소비하고(진행 중인 Saga 의 응답·커맨드), 스케줄 작업(타임아웃 재전송·결제 보정)도 돈다. 스케줄러 relay 로 돌던 배포를 Debezium 으로 바꾸는 업그레이드에서 그대로 생긴다(Codex P0 지적)
- 결정: `OutboxWriter` 가 Debezium 모드에서 쓰기 전에 **이 DB 에 logical slot 이 있고 outbox 가 publication 에 들어 있는지** 확인한다(`DebeziumRelayReadiness`). 없으면 `OutboxNotReadyException` 으로 트랜잭션을 롤백한다
  - Kafka 리스너: 처리 기록(`processed_message`)까지 함께 롤백되고, 에러 핸들러가 일시 오류로 보고 재시도한다(DLT 로 보내지 않음). 커넥터가 slot 을 만들면 이어서 처리
  - 스케줄 작업: 다음 주기에 다시. HTTP 쓰기(주문·가입): 실패 응답, 다시 보내면 된다(주문은 Idempotency-Key)
  - 준비된 뒤에는 5초 동안 결과를 재사용하고, 준비 전에는 매번 조회한다. slot 을 지우면 5초 안에 다시 막힌다
- 기본값: Debezium 모드 `OutboxWriter` 는 이 확인을 기본으로 한다(빠뜨려도 안전한 쪽). 스케줄러 모드는 행이 남으므로 확인하지 않는다
- 감수한 것: slot 이 생길 때까지 그 서비스의 쓰기가 멈춘다(유실 대신 지연). 커넥터가 계속 안 뜨면 `DebeziumSlotCheck` 경고와 Kafka lag 경보(`ConsumerLagHigh`)로 보인다

## K-3 추가 결정 (ArgoCD, edge)

- **ArgoCD app-of-apps**: `deploy/argocd/root.yaml` 하나만 apply 한다. 자식 Application(Strimzi, Envoy Gateway, commerce-saga)은 `deploy/argocd/apps` 의 작은 Helm 차트라 `revision` 값 하나로 모두 같은 커밋을 본다. 플랫폼이 먼저(sync-wave -2), CRD 가 아직 없을 때는 dry-run 을 건너뛰고 재시도한다. CI 는 PR 커밋으로 sync 해 Synced·Healthy 를 확인한다
- **edge 는 Gateway API(`Gateway`·`HTTPRoute`) + Envoy Gateway**. ingress-nginx 가 2026년에 은퇴해 Ingress 대신 표준의 다음 API 를 쓴다. **화면(Next)만 밖으로 연다**. 브라우저는 Next 만 보고 Next 서버만 Spring Gateway 를 부르므로(ADR 0011) Spring Gateway·서비스·DB·Kafka·Redis 는 내부망에 둔다(AGENTS "외부 공개는 edge 만")
- Envoy 의 Service 타입은 값으로 둔다. kind 는 LoadBalancer 가 없어 ClusterIP(LoadBalancer 면 주소를 못 받아 Gateway 가 Programmed 가 되지 않는다)
- 감수한 것: 이미지는 레지스트리 없이 `kind load` 라 ArgoCD 만으로 끝나지 않는다(CI·로컬 스크립트가 먼저 넣는다). ArgoCD 는 공개 GitHub 저장소를 읽는다

## K-4 추가 결정 (EKS: GHCR + ArgoCD GitOps)

- **CD 는 pull 방식**: CI(`release` 워크플로)는 이미지를 GHCR 에 올리고 배포 브랜치 `release/eks` 를 옮기기만 한다. 클러스터 안의 ArgoCD 가 그 브랜치를 보고 sync 한다. CI 에 클러스터 자격증명을 두지 않는다
- **레지스트리는 GHCR(public)**: `GITHUB_TOKEN` 으로 push, AWS 쪽 설정(ECR·IAM OIDC) 없음, 노드는 인증 없이 pull
- **개발용 시드 사용자를 넣지 않는다**: 인터넷에 열리므로 EKS 값에서 `AUTH_FLYWAY_LOCATIONS` 를 `classpath:db/migration` 만으로 둔다(저장소에 공개된 admin 비밀번호로 결제 장애 주입이 가능해지는 것을 막음). 가입자 id 를 1000 부터 시작하는 구문은 시드에만 있었으므로 auth-service `V3__user_id_starts_at_1000` 으로 옮겨, 시드 없이도 가입자가 point-service 의 시연용 계정(1~4)을 물려받지 않게 한다
- **이미지 태그 = 커밋 해시 = ArgoCD 가 sync 하는 revision**: EKS root(`deploy/argocd/eks/root.yaml`)가 `$ARGOCD_APP_REVISION` 을 자식의 `revision`·`imageTag` 로 넘긴다. main 은 ruleset 으로 PR 만 받아 태그를 Git 에 커밋하는 봇이 어렵고, ArgoCD Image Updater 는 root 의 selfHeal 과 부딪친다. 브랜치를 이미지 push 뒤에만 옮기므로 이미지 없는 커밋이 배포되지 않는다
- 감수한 것: 서비스 하나만 바뀌어도 9개를 다시 빌드하고 모두 새 태그로 롤링한다(태그 = 커밋). http(TLS 전)라 Secure 쿠키를 임시로 끈다. 이미지는 amd64 만
