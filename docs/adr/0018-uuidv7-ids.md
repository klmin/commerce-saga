# ADR 0018. 사용자·상품·알림 id 를 UUIDv7 로 (출시 전 일괄 변경)

- 상태: 채택 (후속 6, 구현은 6-1 ~ 6-3)
- 날짜: 2026-11-27

## 배경

지금 id 는 두 가지가 섞여 있다.

| id | 지금 | 만드는 곳 |
|---|---|---|
| 주문(`orderId`), 결제, 포인트 지급, outbox(`messageId`) | UUIDv7 | 앱(`UuidCreator.getTimeOrderedEpoch()`), 저장 전 |
| 사용자(`userId`) | bigint | auth-service DB IDENTITY |
| 상품(`productId`) | bigint | product-service DB IDENTITY |
| 알림 | bigint | notification-service DB IDENTITY |
| 상품 가격 이력·관리 이력 | bigint | product-service DB IDENTITY |

주문 쪽은 저장 전에 id 가 필요해서(같은 트랜잭션의 outbox 커맨드에 싣는다) 처음부터 UUIDv7 이었다. 사용자·상품은 서비스 안에서 만들어져 숫자 IDENTITY 로 두었는데, 이 id 들은 메시지 계약·토큰 `sub`·다른 서비스 DB·CDC·검색 색인·화면 주소로 서비스 밖까지 퍼진다.

- 순번이라 가입자 수·상품 수·알림 수(알림 커서 `before`)가 밖에서 보인다
- 외부 IdP(Keycloak 등)로 옮기면 `sub` 가 UUID 라 숫자 userId 를 따로 claim 으로 매핑해야 한다
- 데이터가 쌓인 뒤 바꾸면 PK 를 그대로 두고 공개 id 컬럼을 덧대거나, 옛·새 메시지 타입을 함께 받는 기간이 필요하다

아직 운영 데이터와 배포된 옛 버전 소비자가 없다(포트폴리오, 모든 환경을 새로 만들 수 있다). 바꾼다면 지금이 가장 싸다.

## 결정

1. **서비스 밖으로 나가는 id 는 UUIDv7** 로 한다: `userId`, `productId`, 알림 id. DB 컬럼은 `uuid`, 메시지 계약·토큰·API 는 UUID(문자열 표기)
2. **id 는 앱이 저장 전에 만든다** (`UuidCreator.getTimeOrderedEpoch()`, 주문과 같은 방식). 애그리거트를 만들 때 id 가 정해지므로 같은 트랜잭션에서 바로 메시지·응답에 쓸 수 있다. DB 기본값(`uuidv7()`)은 두지 않는다(만드는 곳을 하나로)
3. **서비스 안에서만 쓰는 추가 전용 로그는 bigint IDENTITY 그대로**: 상품 가격 이력, 상품 관리 이력. 밖에서 참조하지 않고, INSERT 순서가 곧 정렬 순서라 숫자가 오히려 맞다. 이 표의 `changed_by` 는 userId 라 UUID 가 된다
4. 토큰 `sub` 는 userId(UUID 문자열). 서비스는 `@CurrentUserId UUID userId` 로 받는다
5. 알림 목록 커서(`before`)는 알림 id(UUIDv7). v7 은 앞부분이 시각이라 id 순 = 시간 순이 유지된다
6. 시드 데이터(상품·재고·포인트 계정 등)는 서비스 사이에 맞아야 하므로 **고정 UUID 리터럴**을 쓴다(`01900000-0000-7000-8000-00000000000N` 형태, 유효한 v7 형식)

### 출시 전 일괄 변경 (일시 예외)

운영 데이터·배포된 소비자가 없으므로 이번 전환은 무중단 절차를 밟지 않는다.

- **머지된 Flyway 마이그레이션을 직접 고친다** (새 `V{n}` 로 ALTER 하지 않는다). 체크섬이 바뀌므로 로컬·compose 볼륨, k8s DB PVC 를 지우고 새로 만든다
- **메시지 계약의 타입을 제자리에서 바꾼다** (`long userId` → `UUID userId`, 새 메시지 타입을 만들지 않는다). Kafka 토픽에 남은 옛 메시지는 환경을 새로 만들 때 함께 지운다
- AGENTS.md 의 "머지된 마이그레이션은 고치지 않는다", "메시지 스키마는 필드 추가만"에 **이 ADR 동안만** 예외 한 줄을 둔다. 전환이 끝나면(6-3) 예외 줄을 지운다. 이후 같은 변경은 다시 규칙(새 마이그레이션·새 메시지 타입)을 따른다

## 순서

| 단계 | 범위 |
|---|---|
| 6-0 | 이 ADR, AGENTS 예외 한 줄 |
| 6-1 | `productId`: product(상품·이력의 `product_id`), inventory(재고·예약 줄), order(주문 줄·장바구니), `ReserveStock`, CDC(`product.cdc.products` 키·열)·search(색인 문서 id), 화면·스크립트·시드 |
| 6-2 | `userId`: auth(회원, 토큰 `sub`, 기기 기록, `UserRegistered`), point·order·payment·notification(사용자 키, 주문·결제·포인트 메시지), `changed_by`, gateway(`/api/me`), 알림 id·커서, 화면·스크립트·시드 |
| 6-3 | AGENTS 예외 줄 제거, PROGRESS·README 정리, 태그 |

## 결과

- 밖에서 보이는 id 에 순번이 없다. 외부 IdP 로 옮겨도 `sub` 를 그대로 userId 로 쓸 수 있다
- id 가 8바이트 → 16바이트. v7 이라 INSERT 시 인덱스가 흩어지지 않는다
- 로그·디버깅에서 `userId=7` 대신 긴 문자열을 본다
- 기존 환경은 모두 초기화해야 한다(한 번)
