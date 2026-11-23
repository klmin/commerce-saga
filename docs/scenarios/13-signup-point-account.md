# 시나리오 13. 가입 → 포인트 계정 (auth.events 코레오그래피)

새로 가입한 사용자에게 포인트 계정이 생기고(가입 축하 1,000P), 그 포인트로 주문할 수 있는지 확인한다. 전에는 포인트 시드(사용자 1~4)만 계정이 있어서 가입한 사용자가 포인트를 쓰는 주문은 포인트 단계에서 실패하고 보상으로 끝났다.

서비스 사이 직접 호출 없이 이벤트로 잇는다. auth-service는 누가 듣는지 모르고 "가입했다"는 사실만 알린다.

## 흐름

```
POST /auth/signup
  auth-service: users INSERT + outbox(UserRegistered, auth.events, 키 = userId)   ← 한 트랜잭션
  relay(스케줄러 또는 Debezium auth-outbox 커넥터) → auth.events
point-service AuthEventsListener
  processed_message INSERT (같은 메시지 재전달이면 건너뜀)
  point_accounts INSERT … on conflict (user_id) do nothing (잔액 = 가입 축하 포인트)   ← 한 트랜잭션
```

| 항목 | 결정 |
|---|---|
| 메시지 | `UserRegistered(userId, registeredAt)`, 토픽 `auth.events`(auth-service 소유), 키 `userId`. 아이디·비밀번호 같은 개인정보는 싣지 않는다 |
| 원자성 | 사용자 저장과 outbox INSERT가 같은 `@Transactional`. 가입이 롤백(아이디 중복 등)되면 이벤트도 없다 |
| 멱등 | 같은 메시지 재전달은 `processed_message`, 내용이 같은 다른 메시지는 `point_accounts` PK(`user_id`)로 거른다. 몇 번 와도 계정 하나, 축하 포인트 한 번. 이미 쓴 잔액을 되돌리지 않는다 |
| 이미 있는 계정 | 시드 사용자(1~4) 등 계정이 있으면 그대로 둔다 |
| 오류 | 이벤트 토픽이라 관심 없는 타입은 건너뛴다. `type` 헤더가 없거나 본문·`id` 헤더·`userId`가 이상하면 `auth.events.DLT` |
| 가입 축하 포인트 | `point.account.signup-bonus`(`POINT_SIGNUP_BONUS`, 기본 1,000) |
| relay | 다른 서비스와 같다. 스케줄러(기본) 또는 Debezium `infra/connect/auth-outbox.json`(slot `auth_outbox_slot`, `auth_svc`에 replication 권한) |
| 계정이 생기기 전 주문 | 가입 직후 아주 짧은 사이에 포인트를 쓰는 주문이 오면 지금처럼 포인트 단계 실패 → 역순 보상 → `CANCELLED` (주문 Saga는 바꾸지 않음) |

## 재현

1. 인프라, 다섯 서비스, auth-service, gateway를 띄운다 (Debezium 모드면 `scripts/register-connectors.sh`가 `auth-outbox`도 등록)
2. `scripts/smoke-signup-points.sh`

CI `e2e`가 두 relay 모드에서 시나리오 10 다음에 돈다. 시나리오 9의 가입(smoke-auth)도 같은 흐름으로 계정을 만든다.

## 기대 결과

| 단계 | 기대 |
|---|---|
| 13a | 새로 가입한 사용자 `GET /api/points/me` → 몇 초 안에 404에서 200, 잔액 1,000P |
| 13b | 그 사용자가 300P를 써서 주문 → `COMPLETED`, 잔액 700P |
| 13c | `point_accounts`에 그 사용자 행 1개, 시드 계정 1~4 그대로 |

테스트로도 확인한다: `AuthServiceTest`(가입 성공 때만 이벤트 1번), `AuthIntegrationTest`(가입하면 outbox에 `auth.events`/`UserRegistered` 1행, 중복 가입은 409이고 행 없음), `PointAccountServiceTest`(재전달·중복·시드 계정·잔액 보존), `AuthEventsListenerTest`(타입 분기, DLT 대상), `PointIntegrationTest`(Kafka로 같은 이벤트 재전달·중복 → 계정 1개·1,000P, 포인트를 쓴 뒤 늦게 온 같은 이벤트가 잔액을 되돌리지 않음).

## 실제 결과

CI `e2e` 결과는 머지 후 다음 PR에서 채운다.

## 한계

- 가입 축하 포인트 지급은 사용 기록(`point_usages`)에 남지 않는다. 포인트 내역 화면(F-2)에서 보여 주려면 지급 기록을 따로 둔다
- 회원 탈퇴·정지 이벤트는 아직 없다
