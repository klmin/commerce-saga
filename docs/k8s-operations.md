# k8s 를 안정적으로 운영하기

이 문서는 차트(`deploy/helm/commerce-saga`)가 지금 무엇을 하고 있는지와, 운영 환경(EKS 등)에서 안정적으로 돌리려면 구성 요소별로 무엇을 더 해야 하는지 정리한다. 배포 방법은 [deploy/README.md](../deploy/README.md), 결정 이유는 [ADR 0014](adr/0014-k8s-deployment.md)·[ADR 0013](adr/0013-observability.md)에 있다.

지금 차트는 **로컬(Docker Desktop k8s)·CI(kind) 기준**이다. 대부분 replicas 가 1 이고, 저장소는 PVC 하나 또는 emptyDir 이다. 문서에서 "현재"는 그 상태를, "운영"은 노드가 여럿인 클러스터에서 노드 하나가 죽어도 서비스가 이어지는 상태를 말한다.

## 1. 공통 원칙

| 항목 | 왜 | 현재 | 운영 |
|---|---|---|---|
| requests / limits | requests 는 스케줄링(자리 잡기) 기준, limits 를 넘으면 OOMKilled | 모든 파드에 있음 | 측정한 최대 사용량의 1.5~2 배를 limit 으로. 메모리 limit 은 꼭 두고, CPU limit 은 두지 않는다(스로틀링) |
| 프로브 | 죽은 파드 재시작(liveness), 준비 안 된 파드에 트래픽 안 보냄(readiness), 느린 기동 보호(startup) | 백엔드 서비스는 세 가지 모두 | 상태가 있는 구성 요소(Postgres·Redis·관측성)도 readiness 를 둔다 |
| replicas ≥ 2 | 파드·노드 하나가 죽어도 응답 | 모두 1 | 무상태 서비스는 2 이상 |
| 분산 배치 | replicas 가 여럿이어도 한 노드에 몰리면 노드 하나에 다 죽는다 | 없음 | `topologySpreadConstraints` 로 노드·가용 영역(AZ)에 나눠 놓는다 |
| PodDisruptionBudget | 노드 교체·업그레이드(drain) 때 한꺼번에 내려가지 않게 | 없음 | replicas 2 이상인 것마다 `minAvailable: 1` |
| 정상 종료 | 롤링 업데이트 때 처리 중인 요청·메시지를 끝내고 내려가기 | Spring Boot 기본(graceful shutdown) | `preStop` sleep 몇 초(엔드포인트에서 빠지는 시간)를 더하고, `terminationGracePeriodSeconds` 를 처리 시간보다 길게 |
| 자동 확장 | 부하에 맞춰 파드 수 조절 | 없음 | HPA(CPU 또는 요청 수). Kafka 컨슈머는 파티션 수(지금 3)보다 많아도 노는 파드만 생긴다 |
| 비밀값 | 저장소에 두지 않는다 | 로컬 개발용 값이 values 에 있음 | External Secrets Operator + AWS Secrets Manager 같은 외부 저장소에서 넣는다 |
| 경보 | 문제를 사람이 알아야 고친다 | Prometheus 규칙만 있음 | Alertmanager → 메신저(Slack·Discord), critical 은 당직 호출(PagerDuty 등) |

## 2. 백엔드 서비스 (order·inventory·point·payment·notification)

- **무상태다.** 상태는 Postgres·Kafka 에 있으니 replicas 를 늘리기만 하면 된다. 2 이상 + 분산 배치 + PDB.
- **Kafka 컨슈머 수 = 파티션 수까지 의미가 있다.** 토픽 파티션이 3 이니 서비스당 3 파드까지 나눠 받는다. 더 늘리려면 파티션부터 늘린다(키 = orderId 라 늘려도 주문 단위 순서는 지켜진다. 단 늘리는 순간 같은 키가 다른 파티션으로 갈 수 있어 진행 중 주문의 순서가 잠깐 깨질 수 있다 → 트래픽 적을 때).
- **리밸런스 대비.** 파드가 뜨고 질 때마다 컨슈머 그룹이 리밸런스한다. 멱등 컨슈머(ADR 0006)라 재전달은 안전하지만, 잦으면 지연이 생긴다. cooperative-sticky 할당과 정상 종료로 줄인다.
- **메모리.** JVM 은 컨테이너 limit 을 보고 힙을 잡는다(기본 25%). 지금 768Mi limit 에서 50~57% 를 쓴다. 힙을 키울 땐 `-XX:MaxRAMPercentage` 로 비율을 정하고, 힙 밖(메타스페이스·스레드·코드 캐시) 몫을 남긴다.
- **DB 커넥션.** 파드 수 × HikariCP 풀 크기가 Postgres `max_connections` 를 넘지 않게. 파드를 늘릴 땐 풀을 줄이거나 PgBouncer 를 둔다.

## 3. auth-service

- **서명 키를 Secret 으로 넣기 전에는 replicas 1 + `strategy: Recreate` 를 유지한다.** 키를 주지 않으면 기동할 때마다 새 임시 키를 만들어, 파드가 둘이면 서로의 토큰을 401 로 거절한다(ADR 0008). 키를 Secret 으로 넣으면 replicas 를 늘리고 RollingUpdate 로 바꾼다.
- 키 교체(rotation)는 JWKS 에 새 키와 옛 키를 함께 실어 두고, 옛 토큰 만료 시간이 지난 뒤 옛 키를 뺀다.
- refresh token 은 Redis 에 있으니 Redis 가 죽으면 로그인 유지가 깨진다 → 아래 Redis 참고.

## 4. gateway · frontend

- 무상태. replicas 2 이상 + 분산 배치 + PDB.
- gateway 의 사용자별 rate limit 은 Redis 에 있어 파드를 늘려도 한도가 공유된다.
- edge(Envoy Gateway)도 2 이상으로. 클라우드 LB 가 앞에 있어 노드가 죽어도 남은 Envoy 로 간다.
- HTTPS 를 붙이면 `COOKIE_SECURE`·`AUTH_COOKIE_SECURE` 를 true 로 되돌린다(values-eks 에 임시로 끈 상태).

## 5. Postgres

현재: StatefulSet 1 개, PVC 2Gi, 서비스별 DB·계정. 이 파드가 죽으면 모든 서비스의 쓰기가 멈춘다(재시작되면 PVC 데이터는 그대로).

운영 선택지:

| 방법 | 장점 | 단점 |
|---|---|---|
| **관리형(RDS / Aurora)** — 권장 | Multi-AZ 자동 장애 조치, 자동 백업·PITR, 패치 | 비용. Debezium 을 쓰려면 `rds.logical_replication=1` 파라미터 필요 |
| 오퍼레이터(CloudNativePG) | 클러스터 안에서 primary + replica, 자동 장애 조치, S3 백업 | 운영 책임이 우리에게 |
| 지금처럼 StatefulSet 1 개 | 단순 | 장애 조치 없음, 백업 직접 |

어느 쪽이든:
- **백업과 복구 연습.** 백업만 있고 복구를 해본 적이 없으면 없는 것과 같다.
- **Debezium replication slot 감시.** Connect 가 멈추면 slot 이 WAL 을 붙잡아 디스크가 찬다. `pg_replication_slots` 의 지연(바이트)에 경보를 건다. 장애 조치 후 slot 이 새 primary 에 없으면 Connect 가 다시 스냅샷을 뜰 수 있다(PG 17+ 의 failover slot 또는 관리형의 지원 여부 확인).
- 디스크 사용률 경보(80%).

## 6. Redis

현재: Deployment 1 개, 영속성 없음. 재시작하면 refresh token·rate limit 카운터가 사라진다(사용자는 다시 로그인).

운영: 관리형(ElastiCache, Multi-AZ 복제 + 자동 장애 조치) 또는 Sentinel/Valkey 오퍼레이터. refresh token 을 잃으면 안 되면 AOF 영속성을 켠다.

## 7. Kafka (Strimzi)

현재: 브로커 1 개(KRaft, 컨트롤러 겸용), PVC 2Gi, replication factor 1. 브로커가 죽으면 모든 메시지 흐름이 멈춘다(재시작되면 데이터는 PVC 에 있음).

운영에서 꼭 바꿀 것:
- **브로커 3 개, 서로 다른 AZ.** `kafka.replicas: 3` 이면 차트가 replication factor 를 3 으로 맞춘다.
- **`min.insync.replicas: 2`.** 지금 1 로 고정이다. 3 브로커에서 1 이면 리더 하나만 받고 ack 한 메시지가 리더와 함께 사라질 수 있다. 프로듀서 `acks=all` + min.insync 2 여야 브로커 하나가 죽어도 유실이 없다.
- **컨트롤러 분리.** 규모가 크면 KafkaNodePool 을 controller 3 + broker N 으로 나눈다.
- **rack awareness.** Strimzi `rack.topologyKey: topology.kubernetes.io/zone` 으로 복제본을 AZ 에 나눠 놓는다.
- **디스크.** 보존 기간 × 유입량으로 크기를 정하고 사용률 경보. 꽉 차면 브로커가 멈춘다.
- **Cruise Control**(Strimzi 내장)로 파티션 재배치. 브로커를 늘린 뒤 기존 파티션은 자동으로 옮겨지지 않는다.
- 감시: under-replicated partitions, offline partitions, 컨슈머 랙(kafka-exporter 가 이미 수집).

대안: 관리형(Amazon MSK). 브로커 운영이 없어지는 대신 Strimzi 리소스(Kafka·KafkaTopic) 대신 MSK 설정을 쓴다.

## 8. Kafka Connect (Debezium)

현재: 워커 1 개, limit 1Gi, 힙 `-Xms256m -Xmx512m` 고정(동적 힙 75% 면 힙 밖과 합쳐 1Gi 를 넘어 OOMKilled 가 반복됐다).

운영:
- **워커 2 개 이상.** 커넥터(서비스별 하나)는 워커에 나눠 배정되고, 워커가 죽으면 남은 워커가 넘겨받는다. 단 Postgres 커넥터는 태스크가 1 개라 동시에 두 워커가 한 커넥터를 처리하지는 않는다 → 이중화이지 처리량 확장은 아니다.
- **힙은 limit 의 50% 안팎으로 고정**하고 힙 밖 몫(약 300Mi)을 남긴다. limit 을 바꾸면 `jvmOptions` 도 같이.
- **커넥터 상태 감시.** 워커가 살아 있어도 커넥터·태스크가 FAILED 일 수 있다. `KafkaConnector` 의 status 또는 Connect REST `/connectors/*/status` 에 경보. FAILED 면 outbox 가 쌓이기만 한다.
- outbox 테이블 행 수(쌓이는지)에 경보. relay 가 멈춘 가장 확실한 신호다.

## 9. 관측성 (Alloy · Tempo · Loki · Prometheus · Grafana)

현재: 모두 replicas 1, 저장은 emptyDir(파드가 바뀌면 사라짐). 로컬에서 1 시간 관찰했을 때 상한 대비 Grafana 81~88%, Alloy 52→72%, tempo 는 상위 5 위 밖.

| 구성 요소 | 현재 위험 | 운영 |
|---|---|---|
| **Alloy** | 하나가 모든 trace·로그·메트릭을 받는다(SPOF). 죽으면 그동안 수집이 끊긴다 | 역할을 나눈다. 수집(OTLP 받기)은 Deployment 2+ 뒤에 Service. 메트릭 긁기는 clustering 으로 대상 나눠 갖기. tail sampling 을 하면 앞단에 `otelcol.exporter.loadbalancing`(routing_key = traceID)을 두어 같은 trace 가 같은 파드로 가게 |
| **Tempo** | 검색이 메모리를 많이 쓴다(512Mi 에서 1 시간 검색 한 번에 OOM). emptyDir | 저장은 S3. 규모가 크면 분산 모드(distributor·ingester·querier 분리). limit 과 `GOMEMLIMIT`(limit 의 ~90%)을 함께 |
| **Loki** | emptyDir | 저장은 S3, 보존 기간 설정. 규모가 크면 SimpleScalable(read/write/backend) |
| **Prometheus** | 보존 2 일, emptyDir, 하나 | 장기 보존·이중화는 Mimir 또는 Thanos(S3). 또는 관리형(Amazon Managed Prometheus)으로 remote_write |
| **Grafana** | sqlite(파드 안), 하나 | 대시보드·데이터소스는 프로비저닝(이미 파일로 관리). 사용자·설정을 남기려면 외부 DB. 메모리는 대시보드를 그릴 때 쓰니 limit 512Mi 이상 |

**자기 자신 감시(meta-monitoring).** 수집이 멈추면 경보도 같이 멈춘다. `MetricsPipelineDown`(alloy 메트릭이 1 분 없으면)이 그 역할이고, 운영이면 클러스터 밖(관리형 서비스의 헬스체크, Dead man's switch 경보)에서 한 번 더 확인한다.

## 10. ArgoCD · GitOps

- `selfHeal` 이 켜져 있어 `kubectl` 로 직접 바꾼 값은 되돌아간다. 설정은 커밋으로만 바꾼다.
- EKS 는 `release/eks` 브랜치를 따른다(release 워크플로가 이미지 빌드 후 force push). main 에 머지한다고 바로 운영에 가지 않는다.
- 운영 ArgoCD 는 HA 매니페스트(argocd-server·repo-server 2+, redis-ha)로 설치한다.
- sync 실패·Degraded 에 경보(ArgoCD Notifications 또는 `argocd_app_info` 메트릭).

## 11. 클러스터 · 노드

- **노드 그룹을 AZ 3 개에 걸친다.** 파드 분산 배치·Kafka rack awareness·RDS Multi-AZ 가 모두 이걸 전제로 한다.
- **노드 자동 확장**: Karpenter 또는 Cluster Autoscaler. 파드 requests 합이 노드 용량을 넘으면 노드를 더한다 → requests 를 실제에 가깝게 잡아야 한다.
- **EBS 는 AZ 에 묶인다.** StatefulSet 파드는 자기 PVC 가 있는 AZ 에서만 다시 뜬다. 그 AZ 노드가 모자라면 Pending → AZ 마다 노드 여유를 둔다(또는 상태를 관리형 서비스로 뺀다).
- 업그레이드: 노드는 하나씩 drain(PDB 가 지켜 준다), 컨트롤 플레인은 관리형이 처리.
- ResourceQuota·LimitRange 로 namespace 별 상한. 실수로 거대한 파드가 클러스터를 다 쓰지 않게.

## 12. 로컬(Docker Desktop k8s)에서

로컬은 노드가 하나라 이중화가 의미 없다. 대신:
- Docker Desktop 에 메모리를 넉넉히(스택 전체 requests 합 + 여유, 12GB 이상 권장). 모자라면 파드가 Pending 이거나 서로 OOM 을 일으킨다.
- 컨테이너별 사용량은 Grafana 또는 `ContainerMemoryNearLimit` 경보(상한의 90% 를 2 분)로 본다. 경보가 울리면 그 구성 요소의 limit 을 올리거나 원인(검색·힙 설정)을 고친다. 지금까지 이렇게 tempo(1Gi + GOMEMLIMIT), Kafka Connect(힙 고정), Grafana(512Mi)를 고쳤다.
- PC 를 재부팅하면 emptyDir 인 관측성 데이터는 사라지고, Postgres·Kafka 는 PVC 에 남는다.

## 우선순위 (운영으로 옮길 때)

1. Postgres → 관리형(RDS) + 백업, Kafka → 브로커 3 + `min.insync.replicas: 2` (데이터 유실 방지)
2. 무상태 서비스 replicas 2 + 분산 배치 + PDB, auth-service 서명 키 Secret
3. 경보를 사람에게(Alertmanager), Connect 커넥터·outbox 적체·replication slot 감시
4. 관측성 저장소 S3, Alloy 역할 분리
5. HPA·노드 자동 확장
