# ADR 0017. 인증 재구성: auth-service 를 Spring Authorization Server(OIDC IdP)로, Gateway 를 BFF 로, 화면을 SPA 3개로

- 상태: 채택 (후속 5, 구현은 5-1 ~ 5-6). 5-2 에서 결정 3 의 refresh 회전을 끔(`reuseRefreshTokens=true`, 이유는 설계 문서 5절)
- 날짜: 2026-11-25
- 대체: [ADR 0008](0008-auth-self-issued-jwt.md) 의 발급자·refresh token·토큰 전달 부분, [ADR 0011](0011-frontend-nextjs-bff-session.md) 전체(Next 서버 BFF). 아래 "대체 범위" 표
- 자세한 흐름·경로·설정값: [인증 재구성 설계](../auth-sas-bff-design.md) (이 ADR 은 결정과 이유, 설계 문서는 구현 기준)

## 배경

후속 2 에서 auth-service 는 화면 없는 자체 JWT API(ADR 0008), 화면은 Next 서버가 토큰을 httpOnly 쿠키로 들고 Gateway 를 부르는 BFF(ADR 0011)로 만들었다. ADR 0008 이 SAS 를 쓰지 않은 이유는 "클라이언트가 Next 하나, SSO·외부 앱 없음, password grant 폐기로 로그인 화면이 SAS 쪽에 있어야 함"이었다.

후속 5 에서 사용자가 정한 방향(2026-11-25):

- 화면을 **사용자·관리자·인증 셋으로 나누고 CSR(SPA)** 로 바꾼다
- 로그인 한 번으로 사용자·관리자 화면을 오가는 **SSO**, 나중에 모바일·외부 앱이 표준(OIDC)으로 붙을 수 있는 구조
- 토큰은 계속 브라우저 JS 에 두지 않는다(XSS)
- 서비스(리소스 서버)의 JWT 검증·`@CurrentUserId`·소유권 검사, Kafka·Saga·outbox 는 바꾸지 않는다

화면이 여럿이고 SSO 를 원하게 되면서 ADR 0008 의 "SAS 를 쓰지 않는 이유"가 사라졌다. 로그인 화면이 IdP 쪽에 있는 것(authorization code 흐름)도 이제는 원하는 모양이다.

## 고려한 안

### 발급자(IdP)

| | 자체 auth-service 에 SSO 를 덧붙임 | **Spring Authorization Server** | Keycloak |
|---|---|---|---|
| SSO·여러 클라이언트·OIDC | 직접 구현(세션·인가 코드·로그아웃 전파) | 표준 구현 | 표준 구현 |
| 회원 저장소·가입 outbox(`UserRegistered`) | 그대로 | **그대로 둘 수 있다**(같은 서비스·같은 DB 안에서 인증만 SAS) | 회원이 Keycloak 으로 감, 가입 이벤트를 따로 연결 |
| 서명 키 주입 방식(ADR 0008) | 그대로 | `JWKSource` 로 연결해 그대로 | Keycloak 방식으로 |
| 운영 | 지금과 같음 | Spring Boot 앱 하나(지금 auth-service) | 별도 서버·DB·업그레이드 |

**SAS 를 고른다.** 표준 OIDC 를 직접 만들지 않으면서, 회원·가입 outbox·서명 키·Flyway 를 지금 auth-service 안에 그대로 둘 수 있다. Keycloak 은 회원과 가입 이벤트가 서비스 밖으로 나가 Saga(가입 → 포인트 계정)와 엮기 어렵고 운영 구성 요소가 하나 늘어난다.

### 토큰을 누가 들고 있나 (화면이 SPA 가 되면서)

| | SPA 가 public client(PKCE) 로 토큰 보관 | **Gateway(SCG) 가 BFF: 세션에 토큰, `TokenRelay`** | Next 서버 BFF 유지(ADR 0011) |
|---|---|---|---|
| XSS 로 토큰 탈취 | 된다(메모리·storage) | 안 된다(브라우저는 세션 쿠키만) | 안 된다 |
| 화면이 SPA 셋 | 각자 토큰 처리 | 화면은 토큰을 모른다 | 화면마다 Next 서버가 필요(CSR 과 맞지 않음) |
| 서비스 쪽 변경 | 없음 | 없음(Bearer 그대로 받음) | 없음 |
| 새 구성 요소 | 없음 | 없음(이미 있는 Gateway 가 맡음) | Next 서버 셋 |

**SCG 를 BFF(OAuth2 클라이언트)로 만든다.** 화면은 정적 파일이 되고, 토큰은 SCG 의 Redis 세션에만 있다. SCG 는 이미 모든 API 요청이 지나가는 곳이라 토큰을 붙이기 가장 자연스럽다. Bearer 로 오는 요청의 JWT 검증 경로는 남겨 둬 나중 모바일·외부 앱(PKCE public client)을 받는다.

### 화면 수

사용자·관리자를 하나로 합치는 안(인증 화면만 따로, 2개)도 봤다. 더 단순하지만 클라이언트가 하나라 SSO 를 보여 줄 곳이 없고, 호스트 방식에서 관리자 화면을 다른 출처로 떼어 내는 격리(사용자 화면 XSS 가 관리자 API 에 닿지 않음, 관리자 세션·IP 제한 분리)를 잃는다. **3개(사용자·관리자·인증)로 둔다**(사용자 결정 2026-11-25). 인증 화면은 IdP 의 로그인 페이지라 어느 안이든 따로다.

### 화면 도구

Next 를 CSR(`output: 'export'`)로 쓰는 안도 봤다. 서버 일(토큰·refresh·API 대리 호출)을 SCG 가 가져가면 Next 의 서버 기능(SSR·Server Action·proxy)이 남지 않고, export 는 빌드 때 모르는 동적 경로(`/orders/[orderId]`)를 우회해야 한다. **Vite + React + TS + Tailwind + shadcn/ui**, `frontend/apps/{web,admin,auth}` + `packages/ui` 로 한다.

### 클라이언트 등록

DCR(동적 클라이언트 등록)은 초기 액세스 토큰이라는 다른 비밀이 필요하고 SCG 파드마다 중복 등록되며 시크릿 전달 문제를 옮길 뿐이다. 클라이언트는 미리 알고 수가 적다(`gateway-web`, `gateway-admin`). **설정 기반 고정 등록**으로 한다. 외부 앱을 받게 되면 그때 켠다.

## 결정

1. **auth-service = SAS(OIDC IdP).** 회원·등록된 클라이언트·인가 기록(`oauth2_registered_client`·`oauth2_authorization`·`oauth2_authorization_consent`)은 `auth_db`(JDBC, Flyway), SSO 로그인 세션은 Redis(Spring Session, 쿠키 `AUTH_SESSION`). 로그인 화면은 인증 SPA 의 `/signin`, 처리는 auth-service `POST /login`, 가입은 `POST /signup`(회원 저장 + `UserRegistered` outbox 한 트랜잭션, 그대로). 1st-party 클라이언트라 동의 화면은 끈다. 만료된 인가 기록은 스케줄러로 지운다
2. **SCG = BFF + OAuth2 클라이언트 + 리소스 서버.** `oauth2Login`, `TokenRelay`, Redis 세션(쿠키 `GW_SESSION`), CSRF(`XSRF-TOKEN` → `X-XSRF-TOKEN`), OIDC RP-initiated logout(SSO 전체 로그아웃). API 요청에 세션이 없으면 리다이렉트가 아니라 401. 요청마다 auth-service 를 부르지 않는다
3. **토큰**: `iss` = `AUTH_ISSUER`(공개 주소), `sub` = `long userId`, `roles` 클레임 유지, access 15분, refresh 는 SAS 기본 회전(`reuseRefreshTokens=false`). 서명 키는 ADR 0008 의 주입 순서(주입 → 로컬 개발 파일 → 임시)를 `JWKSource` 로 그대로 쓴다. 서비스는 `issuer` 문자열과 내부 주소 `AUTH_INTERNAL_URL` 의 JWKS 로 검증한다(주소만 바뀜)
4. **클라이언트 시크릿**: 외부 변수(`GATEWAY_WEB_CLIENT_SECRET` 등) 우선 → 없으면 앱 밖에서 생성(compose init 컨테이너, k8s 스크립트로 Secret, 호스트 로컬은 `~/.commerce-saga` 개발용 파일) → 그래도 없으면 auth-service·SCG 모두 기동 실패. auth-service 는 시작할 때 DB 에 멱등 upsert(`matches` 로 비교해 다르면 해시 갱신, redirect URI·로그아웃 후 주소도 설정값으로 갱신, 동시 첫 기동 충돌 처리). 앱이 시크릿을 만들어 DB 에만 두지 않는다(SCG 가 원문을 알 수 없고, `auth_db` 를 읽으면 서비스 DB 경계 위반)
5. **배치 두 방식, 같은 빌드**: 다른 도메인(호스트 방식, 기본: `example.com`·`admin.`·`auth.`)과 같은 도메인(경로 방식: `/`·`/admin/`·`/auth/`). 앱은 항상 루트에서 돈다고 보고 앞 경로는 프록시(`X-Forwarded-Prefix`)와 실행 설정(nginx 가 넣는 `<base href>`)이 처리한다. 경로 방식은 BFF 세션이 하나라 로그인 클라이언트도 `gateway-web` 하나. 화면은 클라이언트 이름을 모르고 `/oauth2/authorization/auto` 로 시작하며 SCG 가 고른다. 로컬 개발은 포트 + Vite 프록시, compose 는 Caddy(`CADDY_MODE`), k8s 는 Envoy `HTTPRoute`(`edge.mode`)
6. **ADR 0008 에서 들고 오지 않는 것**: refresh family·재사용 탐지·유예 20초·single-flight. refresh token 이 브라우저에 나가지 않고 SCG 세션(서버)에만 있어 탈취 경로가 크게 줄고, 동시 refresh 도 SCG 한 곳에서 일어난다. 필요해지면 SAS 커스터마이징으로 다시 더한다

## 대체 범위

| ADR | 부분 | 후속 5 이후 |
|---|---|---|
| 0008 | 발급: 자체 auth-service, SAS·Keycloak 을 쓰지 않음 | SAS(OIDC IdP) |
| 0008 | API 표(`/auth/login`·`/refresh`·`/logout`·`/logout-all`·`/auth/sessions`·`/.well-known/jwks.json`) | SAS 표준 엔드포인트(`/oauth2/*`, `/.well-known/*`, `/connect/logout`) + `/login`·`/signup`·`/csrf`. 기존 API 는 5-3 까지 유지 후 제거, 기기 관리는 5-6 에서 SSO 세션 기준으로 다시 |
| 0008 | `iss` = `commerce-saga-auth` | `iss` = `AUTH_ISSUER`(공개 URL) |
| 0008 | refresh token: opaque `familyId.secret`, RTR·재사용 탐지·유예, Redis family 저장 | SAS refresh 회전, 토큰은 SCG 세션에만 |
| 0008 | 토큰 전달(refresh 쿠키 `Path=/auth`, `X-Requested-With` CSRF) | 브라우저는 세션 쿠키만(`GW_SESSION`·`AUTH_SESSION`), CSRF 토큰 |
| 0008 | Gateway 라우팅 `/auth/**` → auth-service, `/auth/**` IP rate limit | 인증 경로는 앞단 프록시가 auth-service 로(설계 문서 7-1). 로그인 IP 제한 위치는 5-1·5-2 에서 다시 정함 |
| 0008 | **그대로**: RS256·JWKS 검증, 서명 키 주입 순서, `sub`·`roles`, access 15분, 서비스 재검증·`@CurrentUserId`·소유권, BCrypt·아이디별 로그인 실패 제한, 사용자별 rate limit, 개발용 시드 사용자 | 그대로(로그인 실패 제한은 SAS 인증 단계로 옮김) |
| 0011 | 프레임워크: Next.js App Router | Vite + React SPA 3개 |
| 0011 | 토큰 보관: Next 서버 httpOnly 쿠키(`cs_access`·`cs_refresh`), `proxy.ts` refresh·single-flight, renew | SCG 세션(BFF), 화면은 토큰을 모름 |
| 0011 | Next 가 넘긴 `X-Forwarded-For` 로 Gateway IP 판단(`GATEWAY_TRUSTED_PROXIES`) | 앞단 프록시 기준으로 다시 정함(5-2) |
| 0011 | **그대로**: Tailwind·shadcn/ui, 응답 zod 검증, open redirect 검사 규칙(로그인 뒤 `return_to` 검사로) | 그대로 |

머지된 ADR 0008·0011 의 본문은 고치지 않고, 상태 줄에만 이 ADR 로 일부·전체가 대체됐다고 적는다.

## 결과

- 좋아지는 것: 로그인 한 번으로 화면 사이를 오간다(SSO). 표준 OIDC 라 모바일·외부 앱을 클라이언트 등록만으로 붙일 수 있다. 화면은 정적 파일이라 배포가 단순하고, 토큰은 여전히 브라우저 JS 에 없다. 서비스는 주소 설정만 바뀐다
- 대가: 요청이 SCG 세션(Redis)에 묶인다(Redis 장애면 다시 로그인). Redis 세션이 둘(auth·gateway). 클라이언트 시크릿을 두 앱이 나눠 가져 교체할 때 둘 다 재시작해야 한다(나중 `private_key_jwt`). 화면 이미지가 셋으로 늘고, 두 배치 방식 때문에 프록시 규칙·설정값이 두 벌이다
- 한계: refresh 재사용 탐지가 없다. 이미 나간 access token 은 최대 15분 유효(그대로). 경로 방식에서는 사용자·관리자 화면이 BFF 세션을 공유해 관리자 세션만 따로 끝낼 수 없다

## 확인 (구현 PR 에서)

- 5-1: 클라이언트 upsert(신규·시크릿 교체·동시 첫 기동), 토큰 `sub`·`roles`, 경로가 붙은 issuer 와 `X-Forwarded-Prefix` 의 리다이렉트·쿠키 Path
- 5-2: 로그인 → 토큰 릴레이 → 서비스 검증, 클라이언트 자동 선택, `return_to` 검사, 로그아웃 뒤 다시 로그인하면 로그인 화면(SSO 세션 종료)
- 5-3~5-6: 두 배치 방식 e2e, 관리자 화면 SSO, 기기 관리, 시나리오 문서
