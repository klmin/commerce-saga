# 인증 재구성 설계: SAS IdP + Gateway BFF + 화면 3개 (후속 5)

- 상태: 설계 확정 (2026-11-25 사용자 결정). 구현 전. 첫 PR 에서 ADR 0017 로 결정을 남기고 ADR 0008·0011 의 바뀌는 부분을 "대체됨"으로 표시한다(머지된 ADR 본문은 고치지 않는다)
- 진행 현황·PR 순서는 [PROGRESS 4-7절](PROGRESS.md#4-7-후속-5-인증-재구성-설계-메모-2026-11-25-사용자-결정)

## 1. 왜 바꾸나

지금은 Next 서버가 BFF(토큰을 httpOnly 쿠키로 보관, ADR 0011)이고 auth-service 는 화면 없는 자체 JWT API(ADR 0008)다. 화면을 **사용자·관리자·인증 3개로 나누고 CSR(SPA)로** 바꾸면서 다음을 원한다.

- 로그인 한 번으로 사용자·관리자 화면을 오가는 SSO, 나중에 모바일·외부 앱이 표준(OIDC)으로 붙는 구조
- 토큰은 여전히 브라우저 JS 에 두지 않는다(XSS)
- 서비스(리소스 서버)의 JWT 검증은 바꾸지 않는다 (ADR 0008 "발급자가 바뀌어도 서비스는 그대로")

ADR 0008 이 SAS 를 쓰지 않은 이유(클라이언트 하나, password grant 폐기로 로그인 화면이 SAS 쪽에 있어야 함)는 화면을 나누고 SSO 를 원하게 되면서 사라졌다.

## 2. 구성

```
              ┌─ example.com        사용자 SPA (정적)  ─┐
브라우저 ─────┼─ admin.example.com  관리자 SPA (정적)  ─┼─ /api/** ─▶ SCG (BFF) ─(Bearer 릴레이)─▶ 서비스들
              └─ auth.example.com   인증 화면 (정적)   ─┘                │                      (JWKS 로 검증, 그대로)
                    │ /oauth2/**, /login(POST), /.well-known/**            │ 로그인·토큰 갱신 때만
                    └──────────────▶ auth-service (SAS) ◀──────────────────┘
```

| 구성 요소 | 역할 | 저장 |
|---|---|---|
| **사용자·관리자 SPA** (`frontend/apps/web`, `apps/admin`) | 화면만. 토큰을 모른다. 같은 출처의 `/api/**` 를 쿠키로 부른다 | 없음 |
| **인증 화면** (`frontend/apps/auth`) | 로그인·회원가입(·비밀번호 재설정은 나중). SAS 의 로그인 페이지 | 없음 |
| **SCG** (`services/gateway`) | **BFF + OAuth2 클라이언트**(`oauth2Login`). 세션에 토큰을 보관하고 `TokenRelay` 로 `Authorization: Bearer` 를 붙인다. 토큰 만료 시 refresh 로 자동 갱신. 지금처럼 JWT 검증·역할·rate limit 도 한다 | Redis 세션 ② |
| **auth-service** (`services/auth-service`) | **SAS(인가 서버, OIDC)**. 로그인 화면은 인증 화면이 그리고, 자격 확인·SSO 세션·토큰 발급은 SAS | Postgres `auth_db`(JDBC) + Redis 세션 ① |
| **각 서비스** | 리소스 서버. JWKS 로 검증, `@CurrentUserId`, 소유권 검사. **바꾸지 않는다**(주소만) | 그대로 |

SPA 가 아니라 **SCG 가 OAuth 클라이언트**다. SPA 는 세션 쿠키만 가진다.

## 3. 저장소

| 무엇 | 어디 | 메모 |
|---|---|---|
| 회원(아이디, BCrypt 해시, 역할) | `auth_db` (지금 테이블) | 가입 = 회원 저장 + `UserRegistered` outbox 한 트랜잭션 (그대로) |
| 등록된 클라이언트 | `auth_db` `oauth2_registered_client` | SAS 제공 스키마를 Flyway 로. 시작할 때 upsert (6절) |
| 인가 기록(인가 코드, access·refresh token, id token) | `auth_db` `oauth2_authorization` | 계속 쌓이므로 만료된 행 정리 스케줄러(멱등 기록 정리 PR #74 와 같은 모양) |
| 동의 기록 | `auth_db` `oauth2_authorization_consent` | 우리 클라이언트는 동의 화면을 끈다(1st-party) |
| ① **SSO 로그인 세션** (auth 도메인) | Redis, Spring Session, 키 접두어 `auth:session` | "이 브라우저는 로그인됨". SSO 의 근거 |
| ② **BFF 세션** (화면 도메인) | Redis, Spring Session(reactive), 키 접두어 `gateway:session` | 사용자별 access·refresh token(`OAuth2AuthorizedClient`) |
| 서명 키 | 지금과 같다(주입 → 로컬 개발 파일 → 임시, ADR 0008) | SAS `JWKSource` 로 연결. DB 에 두지 않는다 |

Redis 세션이 **둘**이다. 접두어를 나눠 섞이지 않게 한다. Redis 를 잃으면 다시 로그인하면 될 뿐 데이터 불일치는 없다(지금과 같음).

## 4. 흐름

**첫 로그인 (사용자 화면)**: 호스트는 바뀌지만 각 경로가 어디로 가는지는 7절 라우팅 표 그대로다.

```
① example.com/oauth2/authorization/gateway-web     → SCG          (화면 호스트의 SCG 경로)
     302 ↓
② auth.example.com/oauth2/authorize?client_id=…     → auth-service (SAS)
     SSO 세션 ① 없음 → 302 ↓
③ auth.example.com/signin                           → 인증 화면    (로그인 폼)
     폼 POST ↓
④ auth.example.com/login                            → auth-service (자격 확인, 세션 ① 생성)
     302 ↓ (저장해 둔 ②로)
⑤ auth.example.com/oauth2/authorize                 → SAS 가 인가 코드 발급
     302 ↓
⑥ example.com/login/oauth2/code/gateway-web?code=…  → SCG          (client_id + client_secret 으로 토큰 교환, 세션 ② 생성)
     302 ↓
⑦ example.com/                                      → 사용자 SPA
```

1. SPA 가 `/api/**` 를 불렀는데 BFF 세션이 없으면 SCG 는 `401` 을 준다(API 요청에 리다이렉트를 주지 않는다). SPA 는 로그인 버튼이나 자동으로 ① 로 **페이지 이동**한다
2. SAS 의 로그인 화면은 `/signin`(인증 화면), 로그인 처리는 `POST /login`(auth-service)으로 **경로를 나눈다**: `formLogin().loginPage("/signin").loginProcessingUrl("/login").failureUrl("/signin?error")`. 같은 `/login` 을 메서드로 나누는 라우팅은 하지 않는다
3. 인증 화면은 정적 파일이라 CSRF 토큰을 만들 수 없다 → 화면을 열 때 `GET /csrf`(auth-service)로 토큰을 받고(`CookieCsrfTokenRepository` 가 `XSRF-TOKEN` 쿠키도 줌), 로그인은 **`<form method="post" action="/login">` + 숨은 필드 `_csrf`** 로 보낸다. `fetch` 로 보내면 302 를 브라우저가 화면 이동으로 따라가지 않아 ⑤ 로 이어지지 않는다
4. 실패하면 `/signin?error` 로 돌아오고 화면이 오류 문구를 보인다. 로그인 실패 제한(아이디별 15분 5회)은 ④ 단계에서

**SSO (관리자 화면으로 이동)**: 2와 같은데 3에서 세션 ① 이 이미 있으므로 **로그인 화면 없이** 바로 인가 코드가 온다. 관리자 화면은 클라이언트 `gateway-admin`, 세션 ② 도 `admin.example.com` 쿠키로 따로다.

**API 호출**: SPA → `/api/**`(세션 쿠키 + CSRF 헤더) → SCG 가 세션 ② 에서 access token 을 꺼내 `Bearer` 로 붙여 서비스로. **요청마다 auth-service 를 부르지 않는다.** 서비스는 JWKS 로 검증.

**토큰 갱신**: access token 이 만료되면 SCG 가 refresh token 으로 SAS 에서 새로 받는다(회전, 옛 refresh 는 무효). 브라우저는 모른다.

**로그아웃**: SCG 세션 ② 삭제 → OIDC RP-initiated logout 으로 SAS 의 세션 ① 과 그 사용자의 인가 기록 정리 → 화면으로. "이 화면만 로그아웃"이 아니라 SSO 전체 로그아웃이 기본.

**가입**: 인증 화면 `/register` → **폼 POST** `/signup`(auth-service, `_csrf` 포함) → 회원 + `UserRegistered` outbox(지금 그대로, point 가 포인트 계정 생성) → 바로 로그인 처리(세션 ①) → 저장된 authorize 요청이 있으면 ⑤ 부터 이어서 화면으로, 없으면 사용자 화면으로. 중복 아이디 등 실패는 `/register?error=…` 로 돌아온다.

## 5. 토큰 내용

- `iss`: `AUTH_ISSUER`(auth 공개 주소, 환경별 값은 7-3). 서비스·SCG 는 `issuer` 문자열로 검사하고 공개키는 **내부 주소** `AUTH_INTERNAL_URL` 의 JWKS 로 받는다(컨테이너 안에서 공개 주소가 자기 자신을 가리키는 문제 회피)
- `sub`: 지금처럼 `long userId` (SAS `OAuth2TokenCustomizer`). 서비스의 `@CurrentUserId long` 그대로
- `roles`: 지금 클레임 그대로. 역할 변경은 토큰 갱신 때 반영(지금과 같음)
- access 15분 유지. refresh 는 SAS 기본 회전(`reuseRefreshTokens=false`)

ADR 0008 의 refresh family·재사용 탐지·유예(20초)·single-flight 는 **들고 오지 않는다.** refresh token 이 브라우저에 나가지 않고 SCG 세션(서버)에만 있어 탈취 경로가 크게 줄었고, 동시 refresh 도 SCG 한 곳에서 일어난다. 필요해지면 SAS 커스터마이징으로 다시 더한다(한계에 적음).

## 6. 클라이언트 등록과 시크릿

**등록은 DCR 이 아니라 설정 기반 고정 등록.** 클라이언트(SCG 의 `gateway-web`, `gateway-admin`)는 미리 알고 수가 적다. DCR 은 초기 액세스 토큰이라는 또 다른 비밀이 필요하고, SCG 파드마다 중복 등록되며, 시크릿 전달 문제를 옮길 뿐이다. 외부 앱을 받게 되면 그때 켠다.

| 값 | 비밀 | 맞추는 법 |
|---|---|---|
| `client-id` | 아님 | 설정 상수(양쪽 같은 값) |
| redirect URI | 아님 | 환경별 설정(Helm values·compose env). 등록된 것만 허용 |
| **`client-secret`** | **비밀** | 아래 순서로 **한 출처**에서 두 앱이 같은 값을 받는다 |

**시크릿을 찾는 순서 (auth-service·SCG 같은 규칙)**
1. 외부 변수 `GATEWAY_WEB_CLIENT_SECRET` / `GATEWAY_ADMIN_CLIENT_SECRET` 이 있으면 그 값 (최우선)
2. 환경별로 "없으면 생성" 은 **앱 밖**에서 한다
   - compose: 한 번 도는 `oauth-secret-init` 컨테이너가 공유 볼륨에 파일이 없으면 생성, 있으면 그대로 → 두 앱이 같은 파일을 읽는다
   - k8s: 스크립트가 Secret 이 없으면 생성(`kubectl get` → 없으면 `create`). **ArgoCD 가 관리하는 차트 밖**에서 만든다(차트에 넣으면 덮어쓰거나 지움). 나중에 External Secrets `Password` 생성기로 바꿀 수 있다
3. 호스트에서 띄우는 로컬 개발(`local` 프로파일): 로컬 개발용 파일 `~/.commerce-saga/oauth-client-<id>.secret` 이 있으면 쓰고 없으면 만든다(서명 키의 dev-key-file 과 같은 방식, 원자적 생성 `CREATE_NEW` 후 충돌이면 다시 읽음). 저장소에 값이 남지 않는다
4. 그래도 없으면 **두 앱 모두 기동 실패**(fail-fast). 조용히 만들면 상대와 값이 어긋나 로그인 때 `invalid_client` 로 터진다

**auth-service 가 DB 에 반영하는 규칙 (시작할 때)**
- `client-id` 로 조회 → 없으면 등록(시크릿은 `PasswordEncoder` 해시) / 있으면 `matches(원문, 해시)` 로 비교해 다르면 해시 갱신(시크릿 교체) / 같으면 그대로. redirect URI·scope 도 설정과 다르면 갱신
- 해시끼리 문자열 비교를 하지 않는다(BCrypt 는 매번 해시가 다르다)
- 인스턴스 여럿이 동시에 처음 뜨면 둘 다 INSERT 할 수 있다 → 유니크 충돌이면 다시 읽고 위 규칙을 한 번 더 적용(또는 advisory lock)
- **앱이 시크릿을 만들어 DB 에만 넣지 않는다.** DB 에는 해시만 있어 SCG 가 원문을 알 방법이 없고, SCG 가 `auth_db` 를 읽는 것은 서비스 DB 경계 위반이다

나중에 시크릿 공유 자체를 없애려면 `private_key_jwt`(SCG 가 자기 개인키로 서명한 JWT 로 인증, SAS 에는 공개키만 등록)로 바꾼다.

## 7. 도메인과 라우팅

**운영은 호스트로 나눈다** (경로 + `basePath` 는 경로 예약·쿠키 섞임·앱 사이 링크 문제가 있어 쓰지 않음). 로컬·compose·k8s 세 환경이 **같은 코드, 다른 설정값**으로 돈다. 주소는 코드에 박지 않는다.

### 7-1. 어떤 경로가 어디로 가나 (모든 환경 공통)

**사용자·관리자 화면 쪽** (빠지면 로그인이 SCG 에 닿지 않아 BFF 세션이 만들어지지 않는다)

| 경로 | 보낼 곳 | 용도 |
|---|---|---|
| `/api/**` | SCG | API 호출 (토큰 릴레이) |
| `/oauth2/authorization/**` | SCG | 로그인 시작 (SAS 로 리다이렉트) |
| `/login/oauth2/code/**` | SCG | 인가 코드 콜백 (토큰 교환, 세션 ② 생성) |
| `/logout` | SCG | 로그아웃 (세션 ② 삭제 → SAS OIDC 로그아웃) |
| 그 외 | 그 화면(SPA) | 화면·정적 파일. 모르는 경로는 `index.html`(SPA fallback) |

**인증 쪽**

| 경로 | 보낼 곳 | 용도 |
|---|---|---|
| `/oauth2/**`, `/.well-known/**`, `/userinfo`, `/connect/logout` | auth-service | SAS 표준 엔드포인트 |
| `/login` | auth-service | 로그인 폼 POST 처리 |
| `/signup` | auth-service | 가입 폼 POST 처리 |
| `/csrf` | auth-service | 인증 화면이 CSRF 토큰을 받음 |
| 그 외 (`/signin`, `/register`, 정적 파일) | 인증 화면(SPA) | 로그인·가입 화면. SPA fallback |

화면 라우터는 위 서버 경로를 쓰지 않는다(경로 예약). 이 두 표가 원본이고, Caddyfile·Helm values·Vite 프록시는 이 표를 그대로 옮긴다(바꿀 때 같이 바꾼다).

### 7-2. 환경별 구성

| | **로컬 개발** (호스트에서 실행) | **compose** (docker 로 전체 실행·e2e) | **k8s** (kind·EKS) |
|---|---|---|---|
| 사용자 | `http://localhost:5173` | `http://localhost` | `https://example.com` |
| 관리자 | `http://localhost:5174` | `http://admin.localhost` | `https://admin.example.com` |
| 인증 | `http://localhost:5175` | `http://auth.localhost` | `https://auth.example.com` |
| 7-1 을 누가 나누나 | 각 화면 **Vite dev 서버의 `server.proxy`** | **Caddy** 컨테이너 하나(80) | **Envoy Gateway `HTTPRoute`**(호스트별) |
| SCG·auth-service | 호스트에서 실행(`localhost:8000`, `:8086`) | 컨테이너(`gateway:8000`, `auth-service:8086`) | Service |
| 화면 정적 파일 | Vite dev 서버 | nginx 컨테이너(화면마다) | nginx Deployment(화면마다). S3 + CloudFront 는 나중 선택지 |
| 도메인 | 없음(포트로 구분) | 없음(`*.localhost`: 크롬·엣지·파이어폭스는 hosts 수정 없이 127.0.0.1) | 실제 도메인 |

- **Caddy 는 compose 에서만** 쓴다. 로컬 개발에서는 띄우지 않아도 된다(Vite 프록시가 같은 일을 함)
- compose 로 백엔드만 띄우고 화면은 호스트에서 고치고 싶으면, Caddyfile 의 화면 대상만 `host.docker.internal:5173` 으로 바꾼다(핫 리로드 + 운영과 같은 주소)
- 80 포트를 못 쓰면 `:8080` 으로 열고 아래 설정값의 주소에도 포트를 똑같이 넣는다
- 관리자 호스트에는 접근 제한(IP 허용 목록 등)을 걸 수 있게 둔다(k8s)

```caddyfile
# infra/caddy/Caddyfile (compose)
(scg) {
    @scg path /api/* /oauth2/authorization/* /login/oauth2/code/* /logout
    handle @scg { reverse_proxy gateway:8000 }
}
http://localhost       { import scg; handle { reverse_proxy web:80 } }
http://admin.localhost { import scg; handle { reverse_proxy admin:80 } }
http://auth.localhost {
    @sas path /oauth2/* /.well-known/* /userinfo /connect/logout /login /signup /csrf
    handle @sas { reverse_proxy auth-service:8086 }
    handle      { reverse_proxy auth-front:80 }
}
```

```ts
// frontend/apps/web/vite.config.ts (로컬 개발). admin 은 같은 목록, auth 는 7-1 인증 쪽 목록을 :8086 으로
const scg = { target: "http://localhost:8000", changeOrigin: false };
server: { port: 5173, proxy: { "/api": scg, "/oauth2/authorization": scg, "/login/oauth2/code": scg, "/logout": scg } }
```
`changeOrigin: false` 로 원래 `Host`(`localhost:5173`)를 넘겨야 SCG 가 redirect URI 를 화면 주소로 만든다.

### 7-3. 설정값 (주소는 모두 여기로)

| 설정 | 로컬 개발 (`local` 프로파일 기본값) | compose | k8s |
|---|---|---|---|
| `AUTH_ISSUER` (공개, 토큰 `iss`, 브라우저가 가는 SAS 주소) | `http://localhost:5175` | `http://auth.localhost` | `https://auth.example.com` |
| `AUTH_INTERNAL_URL` (SCG·서비스가 직접 부르는 SAS 주소) | `http://localhost:8086` | `http://auth-service:8086` | `http://auth-service:8086` |
| `WEB_REDIRECT_URI` | `http://localhost:5173/login/oauth2/code/gateway-web` | `http://localhost/login/oauth2/code/gateway-web` | `https://example.com/login/oauth2/code/gateway-web` |
| `ADMIN_REDIRECT_URI` | `http://localhost:5174/login/oauth2/code/gateway-admin` | `http://admin.localhost/login/oauth2/code/gateway-admin` | `https://admin.example.com/login/oauth2/code/gateway-admin` |
| 로그아웃 후 돌아갈 주소 | 화면 주소(위 사용자·관리자) | 〃 | 〃 |
| 쿠키 `Secure` | false(http) | false(http, `*.localhost`) | true |

- auth-service 는 시작할 때 클라이언트를 upsert 하며 **redirect URI 도 이 설정값으로 갱신**한다(6절). 환경을 바꿔도 DB 를 손대지 않는다
- **공개 주소와 내부 주소를 나눈다.** 컨테이너 안에서 `auth.localhost` 는 자기 자신을 가리키므로, SCG 는 `issuer-uri` 자동 탐색(discovery)을 쓰지 않고 주소를 하나씩 준다
- discovery 를 끄면 discovery 문서에서 오던 값이 빠진다. 특히 **OIDC 로그아웃 주소(`end_session_endpoint`)가 없으면 RP-initiated logout 이 SAS 로 가지 않고 SCG 로컬 로그아웃만 된다** → SAS 세션 ① 이 남아 다음 로그인이 화면 없이 바로 통과한다. Spring Boot 속성(`spring.security.oauth2.client.provider.*`)에는 이 메타데이터 자리가 없으므로 **`ReactiveClientRegistrationRepository` 를 코드로** 만든다

```java
// SCG: 클라이언트 등록을 코드로 (gateway-admin 도 같은 모양, redirect URI 만 다름)
ClientRegistration.withRegistrationId("gateway-web")
    .clientId("gateway-web").clientSecret(secret)               // 6절 시크릿 순서
    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}") // forward headers 로 화면 호스트가 들어감
    .scope("openid")
    .issuerUri(AUTH_ISSUER)                                      // ID 토큰 iss 검사 기준 (공개 주소)
    .authorizationUri(AUTH_ISSUER + "/oauth2/authorize")         // 브라우저 → 공개
    .tokenUri(AUTH_INTERNAL_URL + "/oauth2/token")                // SCG → 내부
    .jwkSetUri(AUTH_INTERNAL_URL + "/oauth2/jwks")
    .userInfoUri(AUTH_INTERNAL_URL + "/userinfo")
    .userNameAttributeName("sub")
    .providerConfigurationMetadata(Map.of(
        "end_session_endpoint", AUTH_ISSUER + "/connect/logout")) // 브라우저가 가므로 공개 주소
    .build();
// 로그아웃: OidcClientInitiatedServerLogoutSuccessHandler, post_logout_redirect_uri = {baseUrl}/ (SAS 클라이언트 등록에도 같은 값)
// SAS: AuthorizationServerSettings.issuer = ${AUTH_ISSUER}, OIDC 로그아웃 엔드포인트 켬
// 서비스·SCG 리소스 서버: issuer = ${AUTH_ISSUER} (문자열 검사), jwk-set-uri = ${AUTH_INTERNAL_URL}/oauth2/jwks
```

- 확인(5-2 테스트·시나리오): 로그아웃 뒤 다시 로그인하면 **로그인 화면이 나와야** 한다(SAS 세션 ① 이 끝났는지)

- **SCG·auth-service 가 원래 호스트를 알게 한다**: 둘 다 `server.forward-headers-strategy=framework`(auth-service 는 로그인 화면 `/signin` 으로 보내는 주소를 공개 주소로 만들려고). Caddy·Envoy·Vite 프록시가 넘긴 `Host`·`X-Forwarded-*` 로 redirect URI(`{baseUrl}/login/oauth2/code/{registrationId}`)를 만들고, 호스트에 맞는 클라이언트(`gateway-web`/`gateway-admin`)를 고른다
- **세션 쿠키 이름을 나눈다**: SCG `GW_SESSION`, auth-service `AUTH_SESSION`(모든 환경 공통). 로컬 개발은 포트만 다른 `localhost` 라 쿠키가 포트를 구분하지 않아 같은 이름이면 서로 덮어쓴다. `SameSite` 는 포트를 보지 않아 `Lax` 로 동작한다

- SPA 는 같은 출처의 `/api` 만 부른다 → CORS 없음, 세션 쿠키 `SameSite=Lax`

## 8. 보안 체크

- SCG 가 쿠키 세션이 되므로 **CSRF**: `CookieServerCsrfTokenRepository`(`XSRF-TOKEN` 쿠키 → SPA 가 `X-XSRF-TOKEN` 헤더). GET 은 상태를 바꾸지 않는다
- 인증 화면 → SAS 로그인·가입은 `GET /csrf` 로 받은 토큰을 넣은 폼 POST(4절). 로그인 실패 제한(아이디별 15분 5회)은 지금 로직을 SAS 인증 단계로 옮긴다
- redirect URI 는 등록된 정확한 값만(와일드카드 없음). `state`·PKCE 사용
- 세션 쿠키: `HttpOnly`, 이름 분리(`GW_SESSION`·`AUTH_SESSION`), 운영은 `Secure`(로컬·compose 는 http 라 끔, 7-3), 호스트별 분리
- 메시지에 토큰을 싣지 않는 규칙, 서비스 재검증·소유권 검사는 그대로
- 모바일·외부 앱이 생기면 PKCE public client 로 따로 등록하고 SCG 의 JWT(Bearer) 검증 경로로 받는다(지금 경로를 남겨 둠)

## 9. 바뀌는 것과 그대로인 것

| 그대로 | 바뀜 |
|---|---|
| 서비스 JWT 검증(주소만), `@CurrentUserId`, 소유권 검사 | auth-service: 자체 `/auth/login`·`/refresh` → SAS 엔드포인트. 회원·가입·outbox 는 유지 |
| `long userId`, `roles` 클레임, 서명 키 주입 방식 | Gateway: 리소스 서버 → **BFF + OAuth2 클라이언트 + 리소스 서버**, Redis 세션, CSRF |
| Kafka·Saga·outbox·`UserRegistered` | 프론트: Next(SSR + BFF) → SPA 3개(`frontend/apps/*` + `packages/ui`) |
| Gateway rate limit(사용자별) | 기기 관리(`/sessions`): refresh family → SSO 세션·인가 기록 기준으로 다시 구현 |
| | 배포: Envoy 호스트별 라우트, compose 에 Caddy·시크릿 init, k8s 시크릿 스크립트 |

## 10. PR 나누기 (추천)

| PR | 범위 |
|---|---|
| 5-0 ADR | ADR 0017(이 문서의 결정). 0008·0011 대체 범위 표시 |
| 5-1 auth-service → SAS | SAS 의존성, Flyway(클라이언트·인가·동의 테이블), 클라이언트 upsert + 시크릿 찾는 순서, 토큰 커스터마이저(`sub`·`roles`), Spring Session Redis ①, 임시 로그인 화면(서버 템플릿, 5-4 에서 교체), 인가 기록 정리 스케줄러. **기존 `/auth/*` API 는 5-3 까지 남겨** Next 화면이 계속 동작하게 한다 |
| 5-2 SCG BFF | `oauth2Login`(gateway-web·gateway-admin), `TokenRelay`, Redis 세션 ②, CSRF, 로그아웃(OIDC), 호스트별 클라이언트 선택. Bearer 로 오는 요청의 JWT 검증은 유지 |
| 5-3 사용자 SPA | `frontend/apps/web`(Vite + React + TS + Tailwind + shadcn, `packages/ui`). Next 화면 옮기기, 데이터는 `/api` 호출. compose Caddy·시크릿 init, Helm 호스트 라우트·nginx 이미지, CI e2e. 끝나면 Next·기존 `/auth/*` API 제거 |
| 5-4 인증 화면 | `frontend/apps/auth` 로그인·가입. 5-1 임시 로그인 화면 제거 |
| 5-5 관리자 SPA | `frontend/apps/admin` 분리(장애 주입·상품 관리), `gateway-admin` 로 SSO 확인 |
| 5-6 기기 관리·정리 | 로그인 기기 목록·개별 로그아웃 다시 구현, 시나리오 문서(SSO·로그아웃·시크릿 교체), PROGRESS·README 갱신, 태그 |

각 PR 은 지금 규칙대로(빌드·테스트, Codex 지적 없음 + CI 통과 후 squash 머지).

## 11. PR 에서 정할 것

- SPA 라우터·데이터 라이브러리(React Router / TanStack Router, TanStack Query)
- 기기 관리의 "기기" 단위(SSO 세션 ① 기준 추천)와 화면 위치(인증 화면 또는 사용자 화면)
- SCG 세션 수명·유휴 만료, SSO 세션 수명
- 인가 기록 보존 기간과 정리 주기
- 프론트 모노레포 도구(pnpm workspace 추천, Turborepo 는 필요해지면)

## 12. 한계 (지금 적어 둘 것)

- 요청이 SCG 세션(Redis)에 묶인다. Redis 장애면 로그인된 사용자도 다시 로그인
- refresh 재사용 탐지(ADR 0008)를 들고 오지 않는다(5절)
- 이미 나간 access token 은 최대 15분 유효(지금과 같음)
- 시크릿 공유(client_secret)는 교체할 때 두 앱 재시작이 필요하다 → 나중 `private_key_jwt`
- 화면이 세 개라 배포 단위·이미지가 늘어난다
