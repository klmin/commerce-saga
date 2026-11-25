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

**첫 로그인 (사용자 화면)**
1. SPA 가 `/api/**` 를 불렀는데 BFF 세션이 없다 → SCG 가 `401` (SPA 는 로그인 버튼 또는 자동으로 `/oauth2/authorization/gateway-web` 로 이동). 화면 문서 요청이면 SCG 가 바로 리다이렉트
2. SCG → `auth.example.com/oauth2/authorize?client_id=gateway-web&redirect_uri=…&state&code_challenge(PKCE)`
3. SSO 세션 ① 이 없으므로 SAS 가 로그인 화면(인증 화면)으로 보낸다
4. 인증 화면이 **폼 전체 POST** 로 `auth.example.com/login` (CSRF 토큰 포함, `fetch` 가 아님) → 성공하면 세션 ① 생성 → 저장해 둔 authorize 요청으로 돌아감
5. SAS 가 인가 코드를 `example.com/login/oauth2/code/gateway-web` 로 보냄 → SCG 가 **client_id + client_secret** 으로 토큰 교환 → 세션 ② 에 저장 → 원래 화면으로

**SSO (관리자 화면으로 이동)**: 2와 같은데 3에서 세션 ① 이 이미 있으므로 **로그인 화면 없이** 바로 인가 코드가 온다. 관리자 화면은 클라이언트 `gateway-admin`, 세션 ② 도 `admin.example.com` 쿠키로 따로다.

**API 호출**: SPA → `/api/**`(세션 쿠키 + CSRF 헤더) → SCG 가 세션 ② 에서 access token 을 꺼내 `Bearer` 로 붙여 서비스로. **요청마다 auth-service 를 부르지 않는다.** 서비스는 JWKS 로 검증.

**토큰 갱신**: access token 이 만료되면 SCG 가 refresh token 으로 SAS 에서 새로 받는다(회전, 옛 refresh 는 무효). 브라우저는 모른다.

**로그아웃**: SCG 세션 ② 삭제 → OIDC RP-initiated logout 으로 SAS 의 세션 ① 과 그 사용자의 인가 기록 정리 → 화면으로. "이 화면만 로그아웃"이 아니라 SSO 전체 로그아웃이 기본.

**가입**: 인증 화면 → `auth.example.com` 가입 API → 회원 + `UserRegistered` outbox(지금 그대로, point 가 포인트 계정 생성) → 바로 로그인 처리(세션 ①) → 저장된 authorize 요청이 있으면 이어서 화면으로.

## 5. 토큰 내용

- `iss`: auth 공개 주소(`https://auth.example.com`, 로컬 `http://auth.localhost`). 서비스·SCG 는 `issuer` 문자열로 검사하고 공개키는 **내부 주소**의 `jwk-set-uri` 로 받는다(컨테이너 안에서 공개 주소가 자기 자신을 가리키는 문제 회피)
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

**호스트로 나눈다** (경로 + `basePath` 는 경로 예약·쿠키 섞임·앱 사이 링크 문제가 있어 쓰지 않음).

| 환경 | 사용자 | 관리자 | 인증 | 앞단 |
|---|---|---|---|---|
| k8s | `example.com` | `admin.example.com` | `auth.example.com` | Envoy Gateway `HTTPRoute` 호스트별. 호스트마다 `/api/**` → SCG, 나머지 → 그 화면. `auth` 호스트는 `/oauth2/**`·`/login`(POST)·`/.well-known/**`·가입 API → auth-service |
| compose | `localhost` | `admin.localhost` | `auth.localhost` | **Caddy** 하나(80). `*.localhost` 는 크롬·엣지·파이어폭스가 hosts 수정 없이 127.0.0.1 로 연결 |
| 호스트 개발 | `localhost:5173` | `localhost:5174` | `localhost:5175` | 화면 dev 서버의 프록시로 `/api` → SCG. 화면을 넘나드는 로그인 흐름은 compose(Caddy)로 확인 |

- SPA 는 같은 출처의 `/api` 만 부른다 → CORS 없음, 세션 쿠키 `SameSite=Lax`
- 화면 정적 파일은 k8s 안 nginx 컨테이너로 서빙(지금 kind·EKS 구성 유지). S3 + CloudFront 는 나중 선택지
- 관리자 호스트에는 접근 제한(IP 허용 목록 등)을 걸 수 있게 둔다

## 8. 보안 체크

- SCG 가 쿠키 세션이 되므로 **CSRF**: `CookieServerCsrfTokenRepository`(`XSRF-TOKEN` 쿠키 → SPA 가 `X-XSRF-TOKEN` 헤더). GET 은 상태를 바꾸지 않는다
- 인증 화면 → SAS 로그인은 폼 POST + CSRF(SAS 쪽 Spring Security). 로그인 실패 제한(아이디별 15분 5회)은 지금 로직을 SAS 인증 단계로 옮긴다
- redirect URI 는 등록된 정확한 값만(와일드카드 없음). `state`·PKCE 사용
- 세션 쿠키: `HttpOnly`, `Secure`(로컬 `*.localhost` 는 http 여도 동작), 호스트별 분리
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
