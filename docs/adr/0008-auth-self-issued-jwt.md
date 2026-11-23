# ADR 0008. 인증: 자체 auth-service 가 JWT(RS256 + JWKS)를 발급하고, refresh token 은 회전(RTR)하며 재사용하면 그 기기 세션을 버린다

- 상태: 채택 (후속 2 A-1 auth-service PR #30, A-2 Gateway·서비스 JWT 검증)
- 날짜: 2026-11-23

## 배경

1~3단계는 사용자를 `X-User-Id` 헤더로 대신했다. 후속 2에서 로그인과 화면(Next.js)을 붙인다. 정할 것은 셋이다.

- 누가 토큰을 발급하나: 직접 만든 auth-service, Spring Authorization Server(SAS), Keycloak
- 토큰을 어떻게 검증하나: 요청마다 auth-service 에 묻기, 아니면 서명 검증
- 로그인을 어떻게 유지하나: access token 을 길게, 아니면 짧은 access token + refresh token

조건: 로그인은 자체 회원(아이디·비밀번호) 하나뿐이고, 클라이언트는 우리 Next 앱 하나다. 외부 앱에 로그인을 내주거나 소셜 로그인, 여러 앱 SSO 는 없다.

## 결정

### 발급: 자체 auth-service (SAS·Keycloak 은 쓰지 않음)

| | 자체 auth-service | Spring Authorization Server | Keycloak |
|---|---|---|---|
| 우리 조건(자체 회원 하나, 클라이언트 하나) | 맞음. 필요한 API 만 | OAuth2/OIDC 프로토콜 전체. 쓰지 않는 기능이 대부분 | 별도 서버 운영, 회원 저장소가 서비스 밖 |
| Next 화면에서 아이디·비밀번호로 바로 로그인 | 됨 | password grant 가 OAuth 2.1 에서 폐기돼 authorization code 흐름(리다이렉트 + 로그인 화면은 SAS 쪽)이 필요 | 같은 문제 (로그인 화면이 Keycloak) |
| 외부 앱·소셜 로그인·SSO | 직접 만들어야 함 | 됨 | 됨 |

OAuth2/OIDC 서버는 "다른 앱에 우리 사용자의 권한을 위임"할 때 값을 한다. 지금은 그런 상대가 없다. 필요해지면 **서비스 쪽(resource server)은 그대로 두고 auth-service 만 바꾼다.** 서비스는 JWKS 로 서명만 검증하므로 발급자가 바뀌어도 영향이 없다.

auth-service 는 화면 없이 API 만 둔다 (`auth_db` + Redis). 다른 서비스와 메시지를 주고받지 않아 Kafka·outbox 를 쓰지 않는다.

| API | 인증 | 내용 |
|---|---|---|
| `POST /auth/signup` | 없음 | 아이디(영문 소문자·숫자·밑줄 4~30자, 대소문자 구분 없음) + 비밀번호(8~64자) → `201 {userId}`, 중복 `409` |
| `POST /auth/login` | 없음 | `200 {accessToken, tokenType, expiresIn, expiresAt}` + refresh token 쿠키 |
| `POST /auth/refresh` | refresh 쿠키 | 새 access token (+ 회전한 refresh 쿠키) |
| `POST /auth/logout` | refresh 쿠키 | 이 기기만. 쿠키 삭제 |
| `POST /auth/logout-all` | access token | 이 사용자의 모든 기기 |
| `GET /auth/sessions`, `DELETE /auth/sessions/{id}` | access token | 로그인 기기 목록 / 내 기기 하나 로그아웃 (남의 것은 404) |
| `GET /.well-known/jwks.json` | 없음 | 검증용 공개키 (`Cache-Control: max-age=300`) |

### access token: JWT RS256, 15분, 검증은 공개키로

- 클레임: `iss`(`commerce-saga-auth`), `sub`(userId), `roles`(`["USER"]`, `["ADMIN","USER"]`), `iat`, `exp`. 헤더에 `kid`
- 서명은 RS256(비대칭). 개인키는 auth-service 만 갖고, Gateway·서비스는 JWKS 의 공개키로 검증한다. 공유 비밀(HS256)이면 검증하는 모든 곳이 발급도 할 수 있게 된다
- **요청마다 auth-service 를 부르지 않는다.** 서명과 만료만 보면 된다. 대가로 이미 나간 access token 은 끊을 수 없어 수명을 짧게(15분) 둔다. 즉시 차단이 필요해지면 사용자별 토큰 버전을 Gateway 에서 확인하는 방식을 검토한다
- 사용자 id 는 `bigint` 그대로 `sub` 에 넣는다. 서비스 테이블·메시지의 `userId: long` 과 맞추기 위해서다. 외부에 노출할 일이 생기면 `public_id uuid` 컬럼을 더한다
- 권한은 **역할(roles)만** 쓴다 (`users` ↔ `user_roles`). OAuth `scope` 는 외부 앱에 권한 일부를 위임할 때의 개념이라 앱 하나에는 의미가 없다. Gateway 는 경로별 역할(`/api/admin/**` = ADMIN), 서비스는 `@PreAuthorize` + 소유권 검사 (A-2)
- 키 교체: 새 키로 서명하고, 이전 키는 `auth.jwt.retired-keys` 로 JWKS 에 남겨 그 키로 서명된 토큰이 끝날 때(최대 15분)까지 검증되게 한다. 키를 지정하지 않으면 기동 때 임시 키를 만든다(로컬 개발용, 재시작하면 이전 토큰 무효)

### refresh token: opaque `familyId.secret`, 회전(RTR), 재사용하면 그 family 폐기

refresh token 은 JWT 가 아니라 아무 의미 없는 문자열 `familyId.secret` 이다 (`secret` = 32바이트 난수). 서버가 매번 Redis 에서 상태를 확인하므로 자체 서명이 필요 없고, 서명된 토큰이면 오히려 "서버에 없어도 유효"해 보인다.

- **family = 로그인 한 번(기기 하나).** 로그인할 때마다 새로 만들고 다른 family 는 건드리지 않아 여러 기기에서 동시에 로그인할 수 있다
- 서버에는 secret 원문이 없고 SHA-256 해시만 있다 (split token). Redis 가 새어도 토큰을 만들 수 없다
- **refresh 할 때마다 secret 을 바꾼다(rotation).** 현재 secret 해시와 같으면 새 secret 으로 교체하고 수명을 연장한다(sliding 14일, 처음 로그인부터 절대 30일을 넘지 않음)
- **이미 바뀐 옛 secret 이 오면 탈취로 본다 → family 삭제.** 토큰을 훔친 쪽과 원래 사용자 중 누가 먼저 썼든 다음에 옛 토큰을 쓰는 쪽이 탐지를 일으키고, 그 기기는 다시 로그인해야 한다. 다른 기기(family)는 그대로다. 브리프 시나리오 9의 "전체 세션 폐기"는 그 기기의 세션으로 좁혔다. 다른 기기까지 끊을 근거가 없고, 탈취가 의심되면 사용자가 전체 로그아웃을 할 수 있다
- 형식은 맞지만 모르는 secret(추측)도 같은 탐지로 다룬다. 옛 secret 을 다 보관하지 않으므로 둘을 구분할 수 없다. familyId 만으로 남의 세션을 끊으려면 familyId 를 알아야 하는데, familyId 는 본인 쿠키와 본인 기기 목록에만 나온다
- 비교는 해시끼리 상수 시간(`MessageDigest.isEqual`)
- 정지되거나 지워진 사용자의 family 는 refresh 때 지운다. **역할은 refresh 마다 DB 에서 다시 읽는다** → 역할 변경·정지가 access token 수명(15분) 안에 반영된다

**여러 탭이 거의 동시에 refresh 할 때(오탐 방지).** 탭 두 개가 같은 refresh token 으로 동시에 요청하면, 한쪽이 회전한 뒤 다른 쪽은 "옛 secret" 을 낸 셈이 되어 탐지에 걸린다. 그래서 회전할 때 직전 secret 해시를 **유예(기본 20초) 동안** `previous` 로 남기고, 그 안에 직전 secret 이 오면 **access token 만 다시 주고 회전하지 않는다**(쿠키를 건드리지 않음). 브라우저 쿠키는 탭끼리 공유되므로 먼저 끝난 요청이 심은 새 쿠키가 그대로 남는다. 설계 메모의 "같은 새 토큰을 다시 돌려줌"은 새 secret 원문을 서버에 잠시라도 둬야 해서 이렇게 바꿨다. 유예 안에 옛 토큰을 훔쳐 쓰면 access token 을 받을 수는 있지만 회전은 못 하고, 유예가 끝나면 탐지된다. Next 서버의 single-flight(같은 사용자의 refresh 를 하나로 묶기)는 프론트 PR 에서 더한다.

**동시 회전의 원자성.** "현재 secret 이 아직 그것이면 바꾼다"를 Redis Lua 스크립트 하나로 한다(compare-and-set). 같은 토큰으로 8개가 동시에 와도 회전은 하나만 성공하고, 진 쪽은 다시 읽어 직전 secret = 유예로 끝난다 (`AuthIntegrationTest`).

### 저장: 회원은 Postgres, family·로그인 실패는 Redis

| 키 | 타입 | 내용 |
|---|---|---|
| `auth:family:<familyId>` | hash | userId, agent(User-Agent 200자), `current`·`previous` 해시, 유예 끝, 생성·마지막 사용·만료·절대 만료. 만료 시각에 저절로 지워진다(`PEXPIREAT`) |
| `auth:user:<userId>:families` | sorted set | 사용자의 familyId 목록, 점수 = 만료 시각. 기기 목록·전체 로그아웃용. 끝난 항목은 조회 때 `ZREMRANGEBYSCORE` 로 정리 |
| `auth:login-fail:<loginId>` | string | 실패 횟수. 첫 실패부터 15분 창 |

family 와 목록을 함께 바꾸는 일(로그인, 회전, 삭제, 전체 삭제)은 각각 Lua 스크립트 하나라 원자적이다. Redis 를 잃으면 모두 다시 로그인하면 될 뿐 데이터 불일치가 없어 영속화(RDB/AOF)를 끈다 (`infra/docker-compose.yml`). 로컬 포트는 6380 (6379 는 다른 Redis 가 흔히 씀).

### 비밀번호와 로그인 제한

- BCrypt. 비밀번호는 길이만 본다(8~64자, NIST SP 800-63B). BCrypt 는 앞 72바이트만 쓰므로 UTF-8 72바이트를 넘으면 거부한다(뒤가 조용히 무시되지 않게)
- 없는 아이디·틀린 비밀번호·정지 계정은 같은 401 이다. 없는 아이디에도 더미 해시와 비교해 응답 시간이 같다
- **아이디별로 15분 안에 5번 틀리면 15분 동안 맞는 비밀번호도 `429` + `Retry-After`.** 성공하면 횟수 초기화. 아이디 기준이라 남이 일부러 틀려 내 계정을 잠글 수 있다. 잠금을 짧게 두고 비밀번호 대입 방지를 택했다. IP 기준 제한은 Gateway rate limit(A-2)에서 더한다

### 토큰 전달: refresh token 은 httpOnly 쿠키로만

- refresh token 은 `Set-Cookie: refresh_token=…; HttpOnly; Secure; SameSite=Lax; Path=/auth` 로만 주고받는다. 응답 본문에 넣지 않는다. 브라우저 JS 가 읽을 수 없고(XSS 로 못 훔침), `/auth` 요청에만 실린다
- access token 은 응답 본문. Next 서버가 받아 자기 httpOnly 쿠키로 보관하고 API 호출 때 `Authorization` 헤더에 넣는다 (프론트 PR). 브라우저 localStorage 에 두지 않는다
- CSRF: 쿠키로 인증되는 요청은 refresh·로그아웃뿐이다(나머지 API 는 `Authorization` 헤더로만 인증). 이 둘은 두 겹으로 막는다. (1) 쿠키가 `SameSite=Lax` 라 다른 사이트에서 보낸 POST 에 실리지 않는다. (2) `X-Requested-With` 헤더가 있어야 한다(값 상관없음, 없으면 `403`). 다른 사이트의 폼은 헤더를 붙일 수 없고, 다른 출처의 fetch 가 헤더를 붙이면 CORS preflight 에서 막힌다(CORS 를 열지 않음). 세션이 없어 동기화 토큰 방식은 쓰지 않는다. Spring Security CSRF 검사를 끄지 않고 "헤더 없는 refresh·로그아웃"에만 걸리게 했다

### Gateway 와 서비스: 두 곳에서 검증 (A-2)

```
브라우저 ─(Next 서버)─▶ gateway :8000 ─▶ /auth/**           → auth-service (토큰 없이)
                                       ─▶ /api/<자원>/**    → <서비스>/<자원>/** (Authorization 그대로)
```

- **Spring Cloud Gateway 2026.0 (Gateway 5.1, WebFlux).** 요청을 받아 넘기기만 하는 프록시라 논블로킹이 맞고, 사용자별 rate limit(`RedisRateLimiter`)이 리액티브 쪽에 있다. 서블릿 서비스 공통 설정(`commerce.service-conventions`)과 분리하려고 빌드 설정을 `commerce.boot-app-conventions`(관측성·JWT 검증·테스트) + 서블릿 전용으로 나눴다. 위치는 `services/gateway`(DB 없음)
- 라우팅: `/auth/**`·JWKS → auth-service, `/api/orders|stocks|points|payments|notifications/**` → 각 서비스(`/api` 를 떼고), `/api/admin/payment-faults` → payment-service `/admin/payment-faults`. 그 밖의 경로는 거부
- **1차 검증은 Gateway.** 토큰이 없거나 위조·만료면 `401` 로 끝나 서비스까지 가지 않는다. `/api/admin/**` 은 `ADMIN` 역할. 로그인 전 화면이 쓰는 `GET /api/stocks/**` 와 `/auth/**` 만 토큰 없이 통과
- **서비스가 다시 검증한다.** 내부망이라도 Gateway 를 우회한 요청(같은 네트워크의 다른 프로세스, 설정 실수)을 믿지 않는다. 다섯 서비스 모두 resource server(같은 JWKS·issuer, `roles` → `ROLE_*`)이고 actuator 만 열려 있다. 결제 장애 주입(`/admin/**`)은 서비스에서도 `ADMIN`
- **사용자 = 토큰의 `sub`.** 컨트롤러는 `@CurrentUserId long userId`(= `@AuthenticationPrincipal` 의 `sub`)로 받는다. `X-User-Id` 헤더는 더 읽지 않고, Gateway 가 들어온 `X-User-Id` 를 지운다. 소유권 비교(남의 주문·Saga·결제·알림 → `404`)는 그대로 서비스 안에서
- 서비스끼리 보안 설정을 공유 모듈로 빼지 않았다(서비스 간 모듈 공유 금지). 서비스마다 `SecurityConfig` 와 `CurrentUserId` 를 둔다
- CSRF: `/api/**` 는 Authorization 헤더로만 인증해 CSRF 대상이 아니다. 다만 검사를 끄지 않고 "쿠키가 실린 상태 변경 요청"에만 걸리게 했다(토큰이 없으니 `403`). 쿠키로 인증하는 refresh·로그아웃은 auth-service 가 `X-Requested-With` 헤더로 막는다

### rate limit (Gateway, Redis 토큰 버킷)

| 경로 | 키 | 기본 (초당 / 버스트) |
|---|---|---|
| `/api/**` | 로그인 사용자 `sub` (토큰 없이 열린 경로는 IP) | 20 / 40 (`GATEWAY_USER_RATE`, `GATEWAY_USER_BURST`) |
| `/auth/**` | 클라이언트 IP | 5 / 20 (`GATEWAY_AUTH_RATE`, `GATEWAY_AUTH_BURST`) |

넘으면 `429`. IP 는 TCP 연결 주소다. `X-Forwarded-For` 는 아무나 쓸 수 있어 믿지 않는다. 단 연결이 믿는 프록시(`GATEWAY_TRUSTED_PROXIES`, 화면의 Next 서버)에서 왔으면 그 헤더의 맨 오른쪽 값을 쓴다(F-1a, ADR 0011). 로그인 대입은 auth-service 가 아이디별로도 막는다. Redis 가 죽으면 `RedisRateLimiter` 는 요청을 통과시킨다(제한보다 가용성).

### 개발용 사용자

- auth-service 에 개발용 시드(`db/dev-seed/R__dev_seed_users.sql`): `user1`~`user4` = id 1~4(다른 서비스의 포인트 시드와 같은 id), `admin` = 100(`ADMIN`). 비밀번호는 로컬 개발용 값 `local-dev-pass`. 가입하면 id 는 1000 부터
- 기본 Flyway 위치에 포함되고, **운영은 `AUTH_FLYWAY_LOCATIONS=classpath:db/migration` 으로 뺀다**
- 시나리오 스크립트는 `scripts/lib/auth.sh` 로 이 사용자로 로그인해 `Authorization` 헤더를 붙인다(토큰은 10분 캐시)

## 결과

- 좋은 점: 서비스는 요청마다 auth-service 를 부르지 않고 공개키로 검증한다. refresh token 탈취는 다음 사용 때 탐지되고 그 기기만 끊긴다. 발급자를 OAuth2 서버로 바꿔도 서비스 쪽은 그대로
- 감수하는 것: 이미 나간 access token 은 최대 15분 유효. 아이디 기준 잠금으로 남이 내 계정을 15분 잠글 수 있음. 유예 20초 안의 옛 토큰은 access token 을 받을 수 있음
- 확인: `RefreshFamilyTest`(회전·유예·재사용·만료 규칙), `AuthServiceTest`(로그인 제한, 동시 회전, 역할 재조회, 소유권), `AuthIntegrationTest`(Postgres + Redis, JWKS 로 서명 검증, 동시 refresh 8개 → 회전 1번), [시나리오 9](../scenarios/09-auth-refresh-reuse.md)(CI `e2e`)
- A-2 확인: `GatewayRoutingTest`(Gateway 를 띄워 401·라우팅·헤더·역할·429), 서비스 `@WebMvcTest`(토큰 없으면 401, `sub` 로 소유권, ADMIN), 시나리오 9f~9i(CI `e2e`, 기존 시나리오 스크립트도 전부 토큰으로)

## 한계와 다음

- 가입한 사용자에게는 포인트 계정이 없다(포인트 시드는 1~4). 가입을 다른 서비스에 알릴 일(`UserRegistered` → 포인트 계정 생성)이 필요해지면 auth-service 에 outbox 를 붙인다
- 이미 나간 access token 은 정지·로그아웃 뒤에도 최대 15분 유효
- 키 교체 자동화(주기적 교체, 은퇴 키 정리)는 없다. 설정으로 한다
