# 시나리오 9. 인증: refresh token 재사용 탐지 → 그 기기 세션 폐기, JWT 로 바꾼 뒤의 소유권 검사(IDOR)

> **9a~9e 는 기록으로만 남긴다 (후속 5-3c, 2026-11-26).** 인증을 Spring Authorization Server + Gateway BFF 로 바꾸면서([ADR 0017](../adr/0017-auth-sas-gateway-bff-spa.md)) 자체 refresh token(family·회전·재사용 탐지·기기 로그아웃)과 `/auth/*` API 를 지웠고 `scripts/smoke-auth.sh` 도 지웠다. refresh token 은 이제 브라우저에 나가지 않고 Gateway 세션에만 있다. 로그인 실패 제한은 SAS 로그인 폼에서 그대로 쓴다(`OAuthFlowIntegrationTest`). SSO·로그아웃·로그인 기기는 [시나리오 19](19-sso-logout-devices.md)(5-6). **9f~9i(Gateway·소유권·관리자·rate limit, `scripts/smoke-gateway.sh`)는 그대로 돈다**(토큰은 SAS 의 스크립트용 클라이언트로 받는다, `scripts/lib/auth.sh`)

브리프 시나리오 9는 "남의 주문 ID 로 조회·취소 → 403(IDOR 방지), refresh token 재사용 감지 시 세션 폐기"다. 9a~9e 는 인증(후속 2 A-1, auth-service), 9f~9i 는 Gateway·서비스 JWT 검증과 소유권(A-2)이다.

설계는 [ADR 0008](../adr/0008-auth-self-issued-jwt.md). refresh token 은 `familyId.secret`, refresh 할 때마다 secret 이 바뀌고, 이미 바뀐 옛 secret 이 다시 오면 탈취로 보고 그 family(로그인 한 번 = 기기 하나)를 지운다.

## 재현

1. 인프라(`postgres`, `redis`)와 auth-service(8086)를 띄운다. 동시 refresh 유예를 짧게 두면 빨리 끝난다
   ```bash
   AUTH_REFRESH_GRACE=2s ./gradlew :services:auth-service:bootRun
   ```
2. `GRACE_WAIT=3 scripts/smoke-auth.sh` (유예가 기본 20초면 `GRACE_WAIT` 없이)
   - **9a** 가입 → 같은 아이디 `409` → 로그인. access token 은 본문, refresh token 은 `HttpOnly` 쿠키(본문에 없음). access token 헤더의 `kid` 가 JWKS 에 있고 JWKS 에 개인키 성분(`d`)이 없다. 기기 목록은 access token 이 있어야 하고 지금 기기가 `current: true`
   - **9b** CSRF 헤더(`X-Requested-With`) 없이 쿠키만으로 refresh → `403`. 헤더를 붙여 refresh → 새 refresh token(같은 family, 다른 secret). 바로 다시 옛 토큰으로 refresh(유예 안 = 여러 탭 동시 refresh) → access token 만 주고 쿠키는 그대로
   - **9c** 유예가 지난 뒤 옛 토큰 → `401`. 같은 family 의 새 토큰도 `401` (훔친 쪽이든 원래 사용자든 다시 로그인)
   - **9d** 기기 두 개로 로그인 → 하나 로그아웃 → 그 기기 `401`, 다른 기기는 refresh `200`
   - **9e** 틀린 비밀번호 5번 → 맞는 비밀번호도 `429` + `Retry-After`

CI `e2e` 가 두 relay 모드 모두 서비스 기동 직후 이 스크립트를 돈다 (`AUTH_REFRESH_GRACE=2s`, `GRACE_WAIT=3`).

## 기대 결과

| | 요청 | 응답 | Redis |
|---|---|---|---|
| 로그인 | `POST /auth/login` | `200` access token + `Set-Cookie: refresh_token=F.S1; HttpOnly; SameSite=Lax; Path=/auth` | `auth:family:F` (current = hash(S1)) |
| 회전 | `POST /auth/refresh` (F.S1) | `200` + `Set-Cookie: refresh_token=F.S2` | current = hash(S2), previous = hash(S1) (유예까지) |
| 동시 refresh | `POST /auth/refresh` (F.S1, 유예 안) | `200`, `Set-Cookie` 없음 | 그대로 |
| 재사용 | `POST /auth/refresh` (F.S1, 유예 뒤) | `401` | `auth:family:F` 삭제 |
| 같은 family | `POST /auth/refresh` (F.S2) | `401` | 없음 |

## 실제 결과

로컬(Windows, Docker Desktop, `AUTH_REFRESH_GRACE=2s`) 2026-11-23:

```
== 9a. 가입 → 로그인 → JWKS
  ok  가입 userId=3, 같은 아이디 409
  ok  access token(sub=3, kid=dev-6d4f5505) 본문, refresh token httpOnly 쿠키, JWKS 에 같은 kid
  ok  기기 목록(access token 필요, 없으면 401)
== 9b. refresh 회전, 동시 refresh 유예
  ok  CSRF 헤더 없는 refresh → 403 (쿠키만으로는 안 됨)
  ok  rt1 → rt2 회전 (같은 family …e0ffeb79)
  ok  유예 안에 rt1 다시 → access token 만 (쿠키 그대로)
== 9c. 유예가 지난 뒤 옛 토큰 재사용 → family 폐기 (3초 대기)
  ok  rt1 재사용 → 401
  ok  같은 family 의 rt2 도 401 (다시 로그인해야 함)
== 9d. 로그아웃은 그 기기만
  ok  phone 로그아웃 → phone 401, tablet 은 refresh 200
== 9e. 로그인 실패 5번 → 잠금
  ok  틀린 비밀번호 5번 → 맞는 비밀번호도 429 (Retry-After 899s)
PASS
```

CI(PR #30, `e2e` run 45172504591)에서 두 relay 모드 모두 위와 같은 줄로 PASS 했다. 가입한 사용자가 `userId=1` 인 것은 그때는 시드 사용자가 없었기 때문이다(A-2 부터 가입 id 는 1000 부터).

## 9f~9i. Gateway 와 서비스의 JWT 검증, 소유권 (A-2)

JWT 로 바꾼 뒤에도 남의 리소스를 볼 수 없는지, Gateway 와 서비스가 각각 토큰을 검증하는지 본다. 사용자는 auth-service 개발용 시드(`user1`~`user4` = id 1~4, `admin` = 100)다.

1. 인프라, 다섯 서비스, auth-service, gateway(8000)를 띄운다. rate limit 을 보려면 gateway 를 `GATEWAY_USER_RATE=5 GATEWAY_USER_BURST=10` 으로
2. `scripts/smoke-gateway.sh`
   - **9f** 토큰 없이 Gateway → `401`, 서비스에 직접 → `401`, 위조 토큰 → `401`
   - **9g** 사용자 1 이 Gateway 로 주문 → 본인은 `200`, 사용자 2 는 주문·Saga·결제 모두 `404`. 사용자 2 가 `X-User-Id: 1` 을 붙여도(Gateway 경유든 서비스 직접이든) `404`
   - **9h** `/api/admin/payment-faults`: 일반 사용자 `403`, 관리자 `200`
   - **9i** 사용자 3 이 30번 몰아서 요청 → 일부 `429`, 그동안 사용자 4 는 `200`

브리프는 남의 주문 조회를 `403` 이라고 했지만 `404` 로 둔다(시나리오 0 과 같음). `403` 이면 그 id 의 주문이 있다는 사실이 드러난다.

로컬(Windows, Debezium relay 모드, `GATEWAY_USER_RATE=5 GATEWAY_USER_BURST=10`) 2026-11-23:

```
== 9f. 토큰 없으면 Gateway 도 서비스도 401
  ok  Gateway GET /api/orders/… (토큰 없음) → 401
  ok  order-service 직접 (토큰 없음) → 401
  ok  Gateway (위조 토큰) → 401
== 9g. 사용자 1 의 주문을 사용자 2 가 볼 수 없다
  ok  사용자 1 주문 …9b64376c (Gateway 경유)
  ok  사용자 1 조회 → COMPLETED
  ok  사용자 2 → 사용자 1 주문 → 404
  ok  사용자 2 → 사용자 1 Saga → 404
  ok  사용자 2 → 사용자 1 결제 → 404
  ok  사용자 2 + X-User-Id: 1 헤더 → 404
  ok  사용자 2 + X-User-Id: 1 헤더, 서비스 직접 → 404
== 9h. 관리자 경로는 ADMIN 만
  ok  사용자 1 → /api/admin/payment-faults → 403
  ok  관리자 → /api/admin/payment-faults → 200
== 9i. 사용자별 rate limit
  ok  사용자 3 연속 30번 → 200 19번, 429 11번
  ok  그동안 사용자 4 는 그대로 → 200
PASS
```

CI(PR #31, `e2e` run 45180044249)에서 두 relay 모드 모두 9a~9i PASS. 가입 사용자는 시드 다음 id(`sub=1000`)부터 나왔고, rate limit(초당 5, 버스트 10)은 연속 30번 중 scheduler 모드 429 15번, debezium 모드 429 20번이었다. 9f~9h 의 응답 코드는 위 로컬 결과와 같다.

## 만들면서 정한 것

- 동시 refresh 유예에서 "같은 새 토큰을 다시 돌려준다"(설계 메모)는 새 secret 원문을 서버에 잠시 둬야 해서, **access token 만 주고 쿠키는 건드리지 않는** 쪽으로 바꿨다. 브라우저 쿠키는 탭끼리 공유돼 먼저 끝난 요청이 심은 새 쿠키가 남는다
- 같은 토큰으로 동시에 회전하면 Redis Lua compare-and-set 으로 하나만 성공한다. 8개를 동시에 보내도 회전은 1번이고 family 는 살아 있다 (`AuthIntegrationTest`)
- 재사용 탐지로 지우는 범위는 그 기기(family)다. 다른 기기까지 끊을 근거가 없고, 사용자가 전체 로그아웃(`POST /auth/logout-all`)을 할 수 있다

## 관련 테스트

- `RefreshFamilyTest`: 회전, 유예 안 직전 secret = GRACE, 유예 뒤·두 번 전 secret·모르는 secret = 재사용, sliding 연장과 절대 만료
- `AuthServiceTest`: 로그인 잠금·초기화, 동시 회전(늦은 쪽은 GRACE), 역할 재조회·정지 사용자, 기기 목록·남의 기기 404
- `AuthControllerTest`: 쿠키 속성(`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth`), 유예 응답에 `Set-Cookie` 없음, `429` + `Retry-After`
- `AuthIntegrationTest`: Postgres + Redis, JWKS 공개키로 서명 검증, Redis 에 secret 원문 없음, 재사용 → family 키 삭제, 동시 refresh 8개
- `GatewayRoutingTest`: Gateway 를 띄워 토큰 없음·위조·만료 401(서비스까지 안 감), `/api` 접두사 제거·`Authorization` 전달·`X-User-Id` 제거, 관리자 경로 403/200, 사용자별 rate limit 429
- 서비스 `@WebMvcTest`: 토큰 없으면 401, 토큰의 `sub` 가 사용자(남의 주문·Saga·결제 404), 결제 장애 주입은 ADMIN 만
