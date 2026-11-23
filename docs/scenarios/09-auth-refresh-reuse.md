# 시나리오 9. 인증: refresh token 재사용 탐지 → 그 기기 세션 폐기

브리프 시나리오 9는 "남의 주문 ID 로 조회·취소 → 403(IDOR 방지), refresh token 재사용 감지 시 세션 폐기"다. 이 문서는 **인증 부분**(후속 2 A-1, auth-service)이다. JWT 로 바꾼 뒤의 소유권 검사(IDOR)는 Gateway·서비스 JWT 검증 PR(A-2)에서 이 문서에 덧붙인다. 지금까지의 IDOR 검사(`X-User-Id`)는 시나리오 0.

설계는 [ADR 0008](../adr/0008-auth-self-issued-jwt.md). refresh token 은 `familyId.secret`, refresh 할 때마다 secret 이 바뀌고, 이미 바뀐 옛 secret 이 다시 오면 탈취로 보고 그 family(로그인 한 번 = 기기 하나)를 지운다.

## 재현

1. 인프라(`postgres`, `redis`)와 auth-service(8086)를 띄운다. 동시 refresh 유예를 짧게 두면 빨리 끝난다
   ```bash
   AUTH_REFRESH_GRACE=2s ./gradlew :services:auth-service:bootRun
   ```
2. `GRACE_WAIT=3 scripts/smoke-auth.sh` (유예가 기본 20초면 `GRACE_WAIT` 없이)
   - **9a** 가입 → 같은 아이디 `409` → 로그인. access token 은 본문, refresh token 은 `HttpOnly` 쿠키(본문에 없음). access token 헤더의 `kid` 가 JWKS 에 있고 JWKS 에 개인키 성분(`d`)이 없다. 기기 목록은 access token 이 있어야 하고 지금 기기가 `current: true`
   - **9b** refresh → 새 refresh token(같은 family, 다른 secret). 바로 다시 옛 토큰으로 refresh(유예 안 = 여러 탭 동시 refresh) → access token 만 주고 쿠키는 그대로
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

CI 결과는 이 PR 의 `e2e` 실행 로그로 채운다.

## 만들면서 정한 것

- 동시 refresh 유예에서 "같은 새 토큰을 다시 돌려준다"(설계 메모)는 새 secret 원문을 서버에 잠시 둬야 해서, **access token 만 주고 쿠키는 건드리지 않는** 쪽으로 바꿨다. 브라우저 쿠키는 탭끼리 공유돼 먼저 끝난 요청이 심은 새 쿠키가 남는다
- 같은 토큰으로 동시에 회전하면 Redis Lua compare-and-set 으로 하나만 성공한다. 8개를 동시에 보내도 회전은 1번이고 family 는 살아 있다 (`AuthIntegrationTest`)
- 재사용 탐지로 지우는 범위는 그 기기(family)다. 다른 기기까지 끊을 근거가 없고, 사용자가 전체 로그아웃(`POST /auth/logout-all`)을 할 수 있다

## 관련 테스트

- `RefreshFamilyTest`: 회전, 유예 안 직전 secret = GRACE, 유예 뒤·두 번 전 secret·모르는 secret = 재사용, sliding 연장과 절대 만료
- `AuthServiceTest`: 로그인 잠금·초기화, 동시 회전(늦은 쪽은 GRACE), 역할 재조회·정지 사용자, 기기 목록·남의 기기 404
- `AuthControllerTest`: 쿠키 속성(`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/auth`), 유예 응답에 `Set-Cookie` 없음, `429` + `Retry-After`
- `AuthIntegrationTest`: Postgres + Redis, JWKS 공개키로 서명 검증, Redis 에 secret 원문 없음, 재사용 → family 키 삭제, 동시 refresh 8개
