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

위 그림은 기본인 **호스트 방식**(다른 도메인, 같은 경로)이다. 도메인을 하나만 쓸 때는 **경로 방식**(`example.com/`, `/admin/`, `/auth/`)으로 같은 빌드를 설정만 바꿔 돌린다(7-0·7-4). 이 문서의 주소 예시는 호스트 방식 기준이다.

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
① example.com/oauth2/authorization/auto            → SCG          (화면 호스트의 SCG 경로, SCG 가 gateway-web 으로 고름)
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

1. SPA 가 `/api/**` 를 불렀는데 BFF 세션이 없으면 SCG 는 `401` 을 준다(API 요청에 리다이렉트를 주지 않는다). SPA 는 로그인 버튼이나 자동으로 ① 로 **페이지 이동**한다. 이때 `?return_to=<지금 화면 경로>` 를 붙여 ⑦ 에서 그 화면으로 돌아온다(7-3). 화면은 클라이언트 이름을 모르고 항상 `/oauth2/authorization/auto` 로 시작하며, SCG 가 호스트를 보고 `gateway-web`·`gateway-admin` 중 하나로 바꾼다(7-3 "클라이언트 고르기")
   - 경로 방식이면 ②~⑤ 의 `auth.example.com/…` 이 `example.com/auth/…` 로 바뀔 뿐 순서는 같다(7-4)
2. SAS 의 로그인 화면은 `/signin`(인증 화면), 로그인 처리는 `POST /login`(auth-service)으로 **경로를 나눈다**: `formLogin().loginPage("/signin").loginProcessingUrl("/login").failureUrl("/signin?error")`. 같은 `/login` 을 메서드로 나누는 라우팅은 하지 않는다
3. 인증 화면은 정적 파일이라 CSRF 토큰을 만들 수 없다 → 화면을 열 때 `GET csrf`(auth-service)로 토큰을 **응답 본문**(`{parameterName, token}`)으로 받고, 로그인은 **`<form method="post" action="login">` + 숨은 필드 `_csrf`** 로 보낸다(주소는 `<base>` 기준 상대 경로, 7-4). auth-service 는 CSRF 토큰을 쿠키가 아니라 **세션 ①(`HttpSessionCsrfTokenRepository`)** 에 둔다. SCG 의 `XSRF-TOKEN` 쿠키와 이름·경로가 겹치지 않게 하려는 것이다(로컬 개발은 포트만 다른 `localhost`, 경로 방식은 같은 호스트라 쿠키가 섞인다). `fetch` 로 보내면 302 를 브라우저가 화면 이동으로 따라가지 않아 ⑤ 로 이어지지 않는다
4. 실패하면 `/signin?error` 로 돌아오고 화면이 오류 문구를 보인다. 로그인 실패 제한(아이디별 15분 5회)은 ④ 단계에서

**SSO (관리자 화면으로 이동)**: 호스트 방식이면 ② 에서 세션 ① 이 이미 있으므로 **로그인 화면 없이** 바로 인가 코드가 온다. 관리자 화면은 클라이언트 `gateway-admin`, 세션 ② 도 `admin.example.com` 쿠키로 따로다. 경로 방식이면 세션 ② 를 사용자 화면과 같이 쓰므로 다시 로그인할 일 자체가 없다(클라이언트도 `gateway-web` 하나, 7-4).

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

로컬·compose·k8s 세 환경이 **같은 코드, 다른 설정값**으로 돈다. 주소는 코드에 박지 않는다.

### 7-0. 두 가지 배치: 다른 도메인(호스트 방식, 기본) / 같은 도메인(경로 방식)

| | **호스트 방식** (다른 도메인, 같은 경로) — 기본 | **경로 방식** (같은 도메인, 다른 경로) |
|---|---|---|
| 사용자 | `example.com/` | `example.com/` |
| 관리자 | `admin.example.com/` | `example.com/admin/` |
| 인증 화면·SAS | `auth.example.com/` | `example.com/auth/` |
| SCG 경로(7-1) | 각 화면 호스트의 루트 | 그 도메인의 루트 |
| 언제 | 서브도메인을 쓸 수 있는 곳(운영 k8s 기본) | 도메인을 하나밖에 못 쓰는 곳(IP 만 있는 서버, 사내망 등) |

**원칙: 앱은 항상 자기가 루트(`/`)에서 돈다고 생각한다.** 앞 경로(`/admin`, `/auth`)는 앞단 프록시와 실행 설정이 처리한다. 그래서 **같은 빌드·같은 이미지**가 두 방식에서 그대로 돈다. 바뀌는 것은 프록시 규칙과 7-3 설정값뿐이다. 자세한 차이는 7-4.

### 7-1. 어떤 경로가 어디로 가나 (모든 환경·두 방식 공통, 앱 기준 경로)

아래 경로는 **앱이 받는 경로**다. 경로 방식에서는 앞에 `/admin`·`/auth` 가 붙어 들어오고 프록시가 떼어서 넘긴다(7-4). SCG 경로는 두 방식 모두 루트라 앞 경로가 붙지 않는다.

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

### 7-2. 환경별 구성 (호스트 방식 기준. 경로 방식은 7-4)

| | **로컬 개발** (호스트에서 실행) | **compose** (docker 로 전체 실행·e2e) | **k8s** (kind·EKS) |
|---|---|---|---|
| 사용자 | `http://localhost:5173` | `http://localhost` | `https://example.com` |
| 관리자 | `http://localhost:5174` | `http://admin.localhost` | `https://admin.example.com` |
| 인증 | `http://localhost:5175` | `http://auth.localhost` | `https://auth.example.com` |
| 7-1 을 누가 나누나 | 각 화면 **Vite dev 서버의 `server.proxy`** | **Caddy** 컨테이너 하나(80) | **Envoy Gateway `HTTPRoute`**(호스트별) |
| SCG·auth-service | 호스트에서 실행(`localhost:8000`, `:8086`) | 컨테이너(`gateway:8000`, `auth-service:8086`) | Service |
| 화면 정적 파일 | Vite dev 서버 | nginx 컨테이너(화면마다) | nginx Deployment(화면마다). S3 + CloudFront 는 나중 선택지 |
| 도메인 | 없음(포트로 구분) | 없음(`*.localhost`: 크롬·엣지·파이어폭스는 hosts 수정 없이 127.0.0.1) | 실제 도메인 |

- 로컬 개발은 포트로 나누므로 항상 "다른 출처, 같은 경로"(호스트 방식과 같은 모양)다. 경로 방식은 compose(Caddy)·k8s 에서 설정으로 고른다(7-4)
- **Caddy 는 compose 에서만** 쓴다. 로컬 개발에서는 띄우지 않아도 된다(Vite 프록시가 같은 일을 함)
- compose 로 백엔드만 띄우고 화면은 호스트에서 고치고 싶으면, Caddyfile 의 화면 대상만 `host.docker.internal:5173` 으로 바꾼다(핫 리로드 + 운영과 같은 주소)
- 80 포트를 못 쓰면 `:8080` 으로 열고 아래 설정값의 주소에도 포트를 똑같이 넣는다
- 관리자 호스트에는 접근 제한(IP 허용 목록 등)을 걸 수 있게 둔다(k8s)

```caddyfile
# infra/caddy/Caddyfile (compose)
(scg) {
    request_header -X-Forwarded-Prefix   # 브라우저가 보낸 값은 버린다(7-3)
    @scg path /api/* /oauth2/authorization/* /login/oauth2/code/* /logout
    handle @scg { reverse_proxy gateway:8000 }
}
http://localhost       { import scg; handle { reverse_proxy web:80 } }
http://admin.localhost { import scg; handle { reverse_proxy admin:80 } }
http://auth.localhost {
    request_header -X-Forwarded-Prefix
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
    .redirectUri(WEB_REDIRECT_URI)                               // 설정값 그대로 (5-2: 요청 주소로 만들지 않음, 아래)
    .scope("openid")
    .issuerUri(AUTH_ISSUER)                                      // ID 토큰 iss 검사 기준 (공개 주소)
    .authorizationUri(AUTH_ISSUER + "/oauth2/authorize")         // 브라우저 → 공개
    .tokenUri(AUTH_INTERNAL_URL + "/oauth2/token")                // SCG → 내부
    .jwkSetUri(AUTH_INTERNAL_URL + "/oauth2/jwks")
    // userInfoUri 는 두지 않는다 (5-2: Security 7 은 있으면 로그인 때 부른다. 필요한 값은 ID 토큰에)
    .userNameAttributeName("sub")
    .providerConfigurationMetadata(Map.of(
        "end_session_endpoint", AUTH_ISSUER + "/connect/logout")) // 브라우저가 가므로 공개 주소
    .build();
// 로그아웃: OidcClientInitiatedServerLogoutSuccessHandler, post_logout_redirect_uri = 그 화면의 홈(절대 주소, 아래 "로그아웃 뒤 돌아갈 곳". SAS 클라이언트 등록에도 같은 값)
// SAS: AuthorizationServerSettings.issuer = ${AUTH_ISSUER}, OIDC 로그아웃 엔드포인트 켬
// 서비스·SCG 리소스 서버: issuer = ${AUTH_ISSUER} (문자열 검사), jwk-set-uri = ${AUTH_INTERNAL_URL}/oauth2/jwks
```

- 확인(5-2 테스트·시나리오): 로그아웃 뒤 다시 로그인하면 **로그인 화면이 나와야** 한다(SAS 세션 ① 이 끝났는지)

- **원래 호스트를 알게 한다**: auth-service 는 `server.request.header.forwarded.strategy=framework` + `use-prefix=true`(Boot 4 이름. 옛 `server.forward-headers-strategy` 이고 `X-Forwarded-Prefix` 는 `use-prefix` 를 켜야 읽는다, 5-1 에서 확인. 로그인 화면 `/signin` 으로 보내는 주소를 공개 주소로 만들려고). **SCG 는 이 기본 처리를 쓰지 않는다(5-2)**: 켜면 아무 연결의 헤더나 믿고 연결 주소까지 `X-Forwarded-For` 맨 왼쪽(브라우저가 쓸 수 있는 값)으로 바꿔 rate limit IP 판단이 무너진다. 대신 (1) redirect URI 는 설정값(`WEB_REDIRECT_URI`·`ADMIN_REDIRECT_URI`, auth-service 등록값과 같음)을 그대로 쓰고, (2) 믿는 프록시(`GATEWAY_TRUSTED_PROXIES`, rate limit 과 같은 목록)에서 온 요청만 `X-Forwarded-Proto`·`-Host`·`-Port` 를 요청 주소에 반영한다(`TrustedForwardedHeadersFilter`. 로그인 콜백은 요청 주소가 redirect URI 와 같아야 하는데 TLS 를 앞단에서 끝내면 Gateway 는 http 로 받기 때문). 클라이언트는 그 요청 주소의 호스트(포트 포함)로 고른다
- **프록시는 들어온 `X-Forwarded-*` 를 믿지 않는다**: `framework` 전략은 헤더를 그대로 믿으므로, 브라우저가 직접 넣은 `X-Forwarded-Prefix`·`X-Forwarded-Host` 가 지나가면 auth-service 의 리다이렉트 주소·쿠키 `Path` 와 SCG 의 redirect URI·클라이언트 선택이 조작된다. 앞단(Caddy·Envoy)은 **두 방식 모두** 들어온 `X-Forwarded-Prefix` 를 지우고(경로 방식 `/auth` 경로에서만 자기가 다시 붙임), `X-Forwarded-Host`·`-Proto`·`-For` 는 자기 값으로 덮어쓴다(Caddy `request_header -X-Forwarded-Prefix`, Envoy `RequestHeaderModifier` 의 `remove`/`set`). SCG·auth-service 는 앞단을 거치지 않고는 닿지 않게 둔다(k8s 는 Service 만, 외부 노출은 Envoy 하나)
- **클라이언트 고르기**: 화면은 클라이언트 이름을 몰라 항상 `/oauth2/authorization/auto` 로 시작한다(같은 빌드가 두 방식에서 돌아야 하므로). SCG 의 인가 요청 resolver 가 `auto` 를 실제 등록 id 로 바꾼다: 요청 호스트가 `ADMIN_CLIENT_HOST`(호스트 방식 `admin.example.com`) 면 `gateway-admin`, 아니면 `gateway-web`. 경로 방식은 `ADMIN_CLIENT_HOST` 를 비워 항상 `gateway-web`(7-4). 콜백 `/login/oauth2/code/{registrationId}` 는 고른 id 그대로라 라우팅(7-1)은 바뀌지 않는다
- **로그아웃 뒤 돌아갈 곳**: 화면은 `POST /logout`(폼, `_csrf`) 에 `return_to` 로 자기 홈 경로를 보낸다. SCG 는 이를 그 클라이언트의 허용 목록(`WEB_HOME`·`ADMIN_HOME` 중 하나)과 비교해 `post_logout_redirect_uri` 를 정하고, 목록에 없으면 클라이언트 기본 홈. SAS 는 등록된 정확한 절대 주소만 받으므로 상대 값(`/admin/`)은 `{baseUrl}` 을 붙여 절대 주소로 만들고, 경로 방식에서는 `gateway-web` 에 `{origin}/`·`{origin}/admin/` 둘 다 등록한다(auth-service upsert 가 설정값에서)
- **세션 쿠키 이름을 나눈다**: SCG `GW_SESSION`, auth-service `AUTH_SESSION`(모든 환경 공통). 로컬 개발은 포트만 다른 `localhost` 라 쿠키가 포트를 구분하지 않아 같은 이름이면 서로 덮어쓴다. `SameSite` 는 포트를 보지 않아 `Lax` 로 동작한다

- SPA 는 같은 출처의 `/api` 만 부른다 → CORS 없음, 세션 쿠키 `SameSite=Lax`
- **로그인 뒤 돌아갈 곳**: 화면은 `/oauth2/authorization/auto?return_to=<지금 화면 경로>` 로 로그인을 시작한다(경로 방식 관리자 화면이면 `/admin/…`). SCG 의 필터가 `return_to` 를 검사해(같은 출처의 `/` 로 시작하는 경로만, `//`·`\`·제어문자·스킴 거부: open redirect 방지) 세션에 넣고, 로그인 성공 핸들러가 그리로 보낸다. 없으면 그 클라이언트의 홈(`WEB_HOME`·`ADMIN_HOME`, 7-4). API 요청은 401 만 받으므로 Spring 의 저장된 요청(request cache)에 기대지 않는다

### 7-4. 경로 방식(같은 도메인)으로 돌릴 때

**두 방식에서 앱이 같게 동작하도록 지키는 것**

| 대상 | 규칙 |
|---|---|
| SPA 빌드 | Vite `base: "./"`(상대 경로)로 빌드하고 앞 경로를 빌드에 넣지 않는다 |
| SPA 실행 | nginx 가 시작할 때 `index.html` 에 `<base href="${BASE_PATH}">` 를 넣는다. 호스트 방식은 모두 `/`, 경로 방식은 `/`·`/admin/`·`/auth/`. 깊은 경로(`/admin/orders/1`)에서 새로고침해도 자산이 `/admin/assets/…` 로 잡힌다. 공식 nginx 이미지의 envsubst 는 `/etc/nginx/templates` 의 설정 파일에만 돌므로 `index.html` 은 `/docker-entrypoint.d/` 시작 스크립트가 바꾼다(`BASE_PATH` 는 `/` 로 시작하고 끝나는지 검사, 아니면 기동 실패). `index.html` 은 `Cache-Control: no-cache`, 해시가 붙은 `assets/` 는 오래 캐시 |
| `BASE_PATH` 끝의 `/` | 꼭 붙인다. 브라우저는 `<base>` 의 마지막 `/` 뒤를 파일 이름으로 보고 버려서, `/admin` 이면 `./assets/…` 가 `/assets/…`(사용자 화면 자산)로 간다. 반대로 issuer(`AUTH_ISSUER`)는 뒤에 경로를 이어 붙이는 값이라 `/` 를 붙이지 않는다(토큰 `iss` 는 문자열 그대로 비교). 사용자가 `/admin` 으로 오면 프록시가 `/admin/` 으로 보낸다 |
| SPA 자기 자산 | 코드에서 자기 자산을 루트 절대 경로(`/favicon.ico`)로 부르지 않는다. `<base>` 기준 상대 경로나 Vite import 로 |
| SPA 라우터 | `basename` 을 `<base>` 에서 읽는다(`new URL(document.baseURI).pathname`) |
| SPA 가 부르는 주소 | **SCG 경로(`/api/…`, `/oauth2/authorization/auto`, `/logout`)는 루트 절대 경로**로. 상대 경로로 쓰면 경로 방식에서 `/admin/api` 가 된다. 로그인 클라이언트 이름은 쓰지 않는다(`auto`, 7-3 "클라이언트 고르기") |
| 인증 화면이 부르는 주소 | **auth-service 경로(`login`, `signup`, `csrf`)와 화면 링크(`signin`, `register`)는 `<base>` 기준 상대 경로**로(앞 `/` 없이). 경로 방식에서 `/auth/login` 으로, 호스트 방식에서 `/login` 으로 저절로 맞는다 |
| auth-service(SAS) | 항상 루트에서 돈다(context-path 없음). 경로 방식에서는 프록시가 `/auth` 를 떼고 `X-Forwarded-Prefix: /auth` 를 붙인다. `server.request.header.forwarded.strategy=framework`·`use-prefix=true` 가 이를 읽어 SAS 가 만드는 주소(로그인 화면 리다이렉트 `/signin` 등)와 세션 쿠키 `Path` 에 `/auth` 를 붙인다. 5-1 테스트로 확인(`OAuthPathModeIntegrationTest`: issuer `…/auth` 로 discovery·토큰 `iss`, 로그인 화면 `/auth/signin`, 쿠키 `Path=/auth`) |
| issuer | `AUTH_ISSUER` 로 고정(경로 방식은 `https://example.com/auth` 처럼 경로가 붙는다). discovery 주소도 `…/auth/.well-known/openid-configuration`. **경로가 붙은 issuer 를 SAS 가 그대로 받는지 5-1 에서 테스트로 확인**한다 |
| SCG | 두 방식 모두 그 도메인의 루트. 앞 경로 없음 |

**경로 방식에서 달라지는 것**
- **SCG 세션이 하나다.** 호스트가 같아 `GW_SESSION` 쿠키가 하나라 사용자·관리자 화면이 BFF 세션을 공유한다(로그인·로그아웃이 같이 움직이고, 관리자 세션만 짧게 둘 수 없다)
- 그래서 **경로 방식에서는 로그인 클라이언트를 하나(`gateway-web`)만** 쓴다. 한 세션에서 두 클라이언트로 번갈아 로그인하면 세션의 로그인 주체가 바뀌어 다른 화면의 토큰 릴레이가 엉킨다. 관리자 화면도 `gateway-web` 으로 로그인하고(`ADMIN_CLIENT_HOST` 를 비우면 SCG 가 `auto` 를 늘 `gateway-web` 으로 바꾼다. 화면 코드는 그대로) `return_to=/admin/…` 로 돌아온다. 로그아웃은 `return_to=/admin/` 이면 `/admin/` 으로 돌아온다(7-3 "로그아웃 뒤 돌아갈 곳"). 관리자 권한은 지금처럼 `roles`(Gateway `/api/admin/**`, 서비스)로 검사한다
- 관리자 접근 제한은 호스트가 아니라 경로(`/admin/**` 화면과 관리자 API) 단위로 건다
- `AUTH_SESSION` 쿠키는 `Path=/auth`(위 prefix 처리로 저절로), `GW_SESSION` 은 `/`

**경로 방식 프록시 규칙** (위에서부터 먼저 맞는 것)

| 순서 | 경로 | 처리 |
|---|---|---|
| 1 | `/auth`, `/auth/*` | `/auth` 를 떼고 `X-Forwarded-Prefix: /auth` → 7-1 인증 쪽 표(SAS 경로 → auth-service, 나머지 → 인증 화면) |
| 2 | `/api/*`, `/oauth2/authorization/*`, `/login/oauth2/code/*`, `/logout` | SCG (그대로) |
| 3 | `/admin`, `/admin/*` | `/admin` 을 떼고 → 관리자 SPA (`/admin` 은 `/admin/` 로 리다이렉트) |
| 4 | 그 외 | 사용자 SPA |

```caddyfile
# infra/caddy/Caddyfile.path (compose, 경로 방식). 호스트 방식은 7-2 의 Caddyfile
http://localhost {
    request_header -X-Forwarded-Prefix          # 브라우저가 보낸 값은 버린다(7-3)
    redir /auth /auth/
    handle_path /auth/* {                       # handle_path 가 /auth 를 뗀다
        @sas path /oauth2/* /.well-known/* /userinfo /connect/logout /login /signup /csrf
        handle @sas { reverse_proxy auth-service:8086 { header_up X-Forwarded-Prefix /auth } }
        handle      { reverse_proxy auth-front:80 }
    }
    @scg path /api/* /oauth2/authorization/* /login/oauth2/code/* /logout
    handle @scg { reverse_proxy gateway:8000 }
    redir /admin /admin/
    handle_path /admin/* { reverse_proxy admin:80 }
    handle { reverse_proxy web:80 }
}
```
k8s 는 Envoy `HTTPRoute` 에서 같은 규칙: 경로 매칭 + `URLRewrite`(`ReplacePrefixMatch: /`) + `RequestHeaderModifier`(`/auth` 는 `set: X-Forwarded-Prefix: /auth`, 나머지 규칙은 `remove: X-Forwarded-Prefix`). Helm 값 `edge.mode: host | path` 하나로 고른다. compose 는 `CADDY_MODE=host|path` 로 Caddyfile 을 고른다.

**방식별 설정값** (7-3 표에 더해서. 로컬 개발은 호스트 방식 값)

| 설정 | 호스트 방식 (k8s 예) | 경로 방식 (k8s 예) | 경로 방식 (compose) |
|---|---|---|---|
| `AUTH_ISSUER` | `https://auth.example.com` | `https://example.com/auth` | `http://localhost/auth` |
| `WEB_REDIRECT_URI` | `https://example.com/login/oauth2/code/gateway-web` | 같음 | `http://localhost/login/oauth2/code/gateway-web` |
| 관리자 로그인 클라이언트 | `gateway-admin` (`ADMIN_REDIRECT_URI` = `https://admin.example.com/login/oauth2/code/gateway-admin`) | `gateway-web` (관리자 전용 클라이언트 없음) | `gateway-web` |
| `ADMIN_CLIENT_HOST` (SCG 가 `auto` 를 `gateway-admin` 으로 바꿀 호스트) | `admin.example.com` | (비움 → 늘 `gateway-web`) | (비움) |
| `WEB_HOME` / `ADMIN_HOME` (기본 돌아갈 곳·로그아웃 후) | `https://example.com/` / `https://admin.example.com/` | `/` / `/admin/` | `/` / `/admin/` |
| `gateway-web` 의 `post_logout_redirect_uri` (SAS 등록) | `https://example.com/` | `https://example.com/`, `https://example.com/admin/` | `http://localhost/`, `http://localhost/admin/` |
| `BASE_PATH` (nginx, web·admin·auth) | `/`·`/`·`/` | `/`·`/admin/`·`/auth/` | `/`·`/admin/`·`/auth/` |

## 8. 보안 체크

- SCG 가 쿠키 세션이 되므로 **CSRF**: `CookieServerCsrfTokenRepository`(`XSRF-TOKEN` 쿠키 → SPA 가 `X-XSRF-TOKEN` 헤더). GET 은 상태를 바꾸지 않는다
- 인증 화면 → SAS 로그인·가입은 `GET csrf` 로 받은 토큰을 넣은 폼 POST(4절). auth-service 의 CSRF 토큰은 세션 ① 에 두고 쿠키로 내보내지 않는다(SCG `XSRF-TOKEN` 과 겹침 방지). 로그인 실패 제한(아이디별 15분 5회)은 지금 로직을 SAS 인증 단계로 옮긴다
- redirect URI·로그아웃 후 주소는 등록된 정확한 값만(와일드카드 없음). `state`·PKCE 사용. `return_to` 는 같은 출처 경로만(7-3)
- 앞단 프록시는 들어온 `X-Forwarded-Prefix` 를 지우고 `X-Forwarded-Host`·`-Proto`·`-For` 를 덮어쓴다. SCG·auth-service 는 앞단을 거쳐서만 닿는다(7-3)
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
| 5-1 auth-service → SAS | SAS 의존성, Flyway(클라이언트·인가·동의 테이블), 클라이언트 upsert + 시크릿 찾는 순서, 토큰 커스터마이저(`sub`·`roles`), Spring Session Redis ①, 임시 로그인 화면(서버 템플릿, 5-4 에서 교체), 세션 CSRF + `GET /csrf`(본문으로 토큰), 인가 기록 정리 스케줄러, 클라이언트 upsert 에 `post_logout_redirect_uri` 목록. **경로 방식 확인 테스트**: 경로가 붙은 issuer(`…/auth`)로 discovery·토큰 `iss`, `X-Forwarded-Prefix: /auth` 일 때 `/signin` 리다이렉트·쿠키 `Path`. **기존 `/auth/*` API 는 5-3 까지 남겨** Next 화면이 계속 동작하게 한다 |
| 5-2 SCG BFF | `oauth2Login`(gateway-web·gateway-admin), `TokenRelay`, Redis 세션 ②, CSRF, 로그아웃(OIDC, `return_to` 로 돌아갈 홈 고르기), 클라이언트 고르기(`auto` → `ADMIN_CLIENT_HOST` 면 gateway-admin, 아니면 gateway-web), `return_to` 저장·검사와 성공 후 이동(`WEB_HOME`·`ADMIN_HOME`). Bearer 로 오는 요청의 JWT 검증은 유지. 테스트: 두 방식의 클라이언트 고르기, 위조한 `X-Forwarded-Host`·`return_to` 거부 |
| 5-3 사용자 SPA | `frontend/apps/web`(Vite + React + TS + Tailwind + shadcn, `packages/ui`). Next 화면 옮기기, 데이터는 `/api` 호출. compose Caddy(호스트·경로 두 방식, `CADDY_MODE`)·시크릿 init, Helm `edge.mode`(host 또는 path) 라우트·nginx 이미지(시작 스크립트가 `BASE_PATH` 로 `<base href>` 주입), 프록시의 `X-Forwarded-Prefix` 제거, CI e2e 는 기본 호스트 방식 + 경로 방식 로그인 한 번. 끝나면 Next·기존 `/auth/*` API 제거 |
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
- 경로 방식(같은 도메인)에서는 사용자·관리자 화면이 BFF 세션을 공유하고 로그인 클라이언트도 하나라, 관리자 세션만 따로 끝내거나 수명을 다르게 둘 수 없다(7-4)
- 두 방식을 모두 지원하는 만큼 프록시 규칙·설정값이 두 벌이다. 7-1 표를 원본으로 두고 같이 고친다
