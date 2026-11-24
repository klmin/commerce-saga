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
scripts/k8s-port-forward.sh      # compose 때와 같은 로컬 포트 (8081~8086, gateway 8000, 화면 3500)
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
