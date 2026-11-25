# ADR 0011. 프론트엔드: Next.js(App Router) + Tailwind + shadcn/ui, Next 서버가 토큰을 httpOnly 쿠키로 들고 Gateway 를 부른다

- 상태: 채택 (후속 2 F-1)
- 날짜: 2026-11-23

## 배경

후속 2에서 로그인을 포함한 화면 전체를 붙인다. 백엔드(auth-service, Gateway, 서비스 JWT 검증)는 ADR 0008 로 끝났다. 정할 것은 셋이다.

- 무엇으로 만드나: 프레임워크, UI 라이브러리, 시작점
- 토큰을 어디에 두나: 브라우저 JS(localStorage), 브라우저 쿠키를 백엔드가 직접 심기, 프론트 서버가 쿠키로 들고 대신 부르기(BFF)
- 만료된 access token 을 언제 바꾸나

## 결정

### 프레임워크·UI: Next.js 16 App Router + Tailwind 4 + shadcn/ui, 사용자 공개 템플릿에서 시작

- `klmin/hexagonal-spring-next-template` 의 `frontend/` 를 **파일만** 복사했다(git 이력 없음). 가져온 시점 템플릿 main `4ca64fe`(2026-11-23). `frontend/` 는 PROGRESS 메모에 적은 `61c6ebc` 와 같다(그 사이 `frontend/` 변경 없음)
- 들어 있던 것: App Router·Server Action, Cache Components, `server-only` API 클라이언트(Problem Details → `ApiError`, 10초 타임아웃, 요청 시점 env), zod env, shadcn/ui(new-york), Vitest + Testing Library, Playwright, ESLint·Prettier, Dockerfile(standalone)
- 지운 것: 예시 `items` CRUD, OpenAPI 타입 생성(`api:types`, `openapi-typescript`). 우리 서비스에는 OpenAPI 문서가 없고 API 가 열 개 남짓이라 **응답을 zod 스키마로 직접 검증**한다(사용자 결정)
- MUI 대신 shadcn/ui: 컴포넌트가 패키지가 아니라 코드로 들어와 고치기 쉽고, Tailwind 와 함께 번들이 가볍다(브리프)
- 구조: 템플릿 기본형(라우트 옆에 컴포넌트를 둔다). 화면이 늘면 `features/<기능>/{api,components}`. FSD 는 이 규모에 과하다

### 토큰 보관: Next 서버가 httpOnly 쿠키로 (BFF)

| | localStorage | 브라우저 ↔ 백엔드 쿠키 | **Next 서버가 쿠키로 들고 대신 부름** |
|---|---|---|---|
| XSS 로 토큰 탈취 | 된다 | 안 된다(httpOnly) | 안 된다(httpOnly) |
| 브라우저가 Gateway 를 직접 봄 | 봄(CORS 필요) | 봄(CORS·쿠키 도메인 맞춤 필요) | 안 봄(Next 만 공개) |
| access token 을 붙이는 곳 | 브라우저 JS | 백엔드가 쿠키에서 꺼냄(서비스 쪽 변경) | Next 서버 (`Authorization: Bearer`) |
| 서비스 쪽 변경 | 없음 | 쿠키 인증 추가·CSRF | 없음 (ADR 0008 그대로) |

- 브라우저는 Next 만 본다. Next 서버만 `BACKEND_URL`(Gateway :8000)을 부른다. CORS 가 필요 없다
- 쿠키 두 개, 모두 `HttpOnly`·`Secure`·`SameSite=Lax`·`Path=/`: `cs_access`(access token, 수명 = 토큰 수명 − 30초), `cs_refresh`(refresh token, 백엔드가 준 Max-Age 14일). 브라우저 JS 는 토큰을 볼 수 없다(`document.cookie` 가 빈 문자열인 것을 Playwright 로 확인)
- 백엔드 auth-service 의 refresh 쿠키(`Path=/auth`)는 브라우저까지 오지 않는다. Next 가 응답의 `Set-Cookie` 에서 값과 Max-Age 를 꺼내 자기 쿠키로 다시 심는다. refresh·로그아웃 때는 `Cookie: refresh_token=…` + `X-Requested-With` 를 붙여 auth-service 의 CSRF 검사를 통과한다
- 로그인·가입·로그아웃은 Server Action. 가입하면 바로 로그인한다
- 화면에 보이는 사용자 번호는 access token 의 `sub` 를 서명 검증 없이 읽은 것(표시용). 권한·소유권은 백엔드가 토큰으로 검사한다(ADR 0008)

### refresh: `proxy.ts` 가 화면 요청 전에

Next 16 에서 Middleware 는 Proxy(`src/proxy.ts`, Node.js 런타임)로 이름이 바뀌었다.

- `cs_access` 가 있으면 통과. 없고 `cs_refresh` 가 있으면 auth-service `/auth/refresh` → 새 쿠키를 응답에 심고, **이번 요청에도** 새 access token 을 실어 서버 컴포넌트가 바로 쓰게 한다
- refresh 가 401/403(만료·폐기·재사용 탐지)이면 두 쿠키를 지우고 `/login?next=<원래 경로>`. 백엔드에 닿지 않으면 쿠키를 그대로 두고 화면이 오류를 보여 준다(다음 요청에서 다시 시도)
- 둘 다 없으면 보호된 화면은 `/login?next=…`. `next` 는 사이트 안 경로만 허용(open redirect 방지). URL 파서가 탭·줄바꿈을 지우고 역슬래시를 `/` 로 읽어 `/	/evil.example` 이 외부 주소가 되므로, 제어문자·공백·역슬래시를 거부하고 파싱한 결과의 origin 까지 본 뒤 그 경로만 돌려준다(PR #35 Codex 리뷰)
- **같은 refresh token 의 동시 요청은 한 번으로 묶는다**(single-flight, 프로세스 안 `Map`). 결과를 10초 남겨, 첫 응답의 새 쿠키가 닿기 전에 옛 쿠키로 온 요청도 같은 새 토큰을 받는다. 그러지 않으면 백엔드가 이미 회전된 토큰의 재사용으로 보고 그 기기 세션을 버린다(ADR 0008). 여러 Next 인스턴스 사이는 백엔드의 동시 refresh 유예(20초)가 받는다
- access 쿠키를 토큰보다 30초 일찍 없애서, 만료 직전 토큰으로 백엔드를 부르다 401 이 나는 일을 줄인다
- access 쿠키가 있는데 백엔드가 401(auth-service 재시작·서명 키 교체로 서명이 틀림)이면 바로 로그인 화면으로 보내지 않고 refresh 를 한 번 시도한다. 서버 컴포넌트는 쿠키를 못 바꾸므로 `/session/renew?next=<지금 경로>`(route handler)로 보내 access 쿠키만 지우고 `next` 로 돌아가면, 다음 요청에서 proxy 가 위 refresh 경로를 탄다. 지금 경로는 proxy 가 요청 헤더 `x-cs-request-path` 로 넘기고 `next` 는 위와 같은 규칙으로 검사한다. renew 는 30초짜리 httpOnly 쿠키 `cs_renewed` 를 남겨, refresh 뒤에도 401(계정 삭제 등)이면 renew 를 반복하지 않고 `/login` 으로 보낸다(로그인하면 표식을 지운다). renew 는 RSC 요청에는 쿠키를 바꾸지 않고 204 를 줘 브라우저가 문서 이동으로 다시 오게 한다: Server Action 의 redirect 를 Next 서버가 미리 따라갈 때는 그 응답의 Set-Cookie 가 브라우저에 가지 않아, 그냥 redirect 하면 renew 주소에 멈춘다(브라우저로 확인)
- 로컬 확인: refresh 성공 → 새 쿠키 2개 / 10초 안 옛 토큰 재요청 → 같은 결과 / 유예 지난 뒤 옛 토큰 → 쿠키 삭제 + `/login?next=%2Forders`, auth-service 로그에 "재사용, 그 기기 세션 폐기"

### Gateway rate limit: BFF 가 넘긴 브라우저 IP 로

- Gateway 의 `/auth/**` rate limit 은 클라이언트 IP 별이다(ADR 0008). BFF 를 거치면 모든 사용자의 로그인·가입·refresh 가 Next 서버 주소 하나로 들어와 **한 버킷을 나눠 쓴다**: 동시 로그인이 429 로 거절되고, 한 사람이 다른 사람의 로그인을 막을 수 있다(PR #35 Codex 리뷰)
- Next 는 auth 호출에 브라우저 IP 를 `X-Forwarded-For` 로 넘긴다. 들어온 요청 `X-Forwarded-For` 의 **맨 오른쪽 값**(Next 앞 프록시가 본 주소, 프록시가 없으면 Next 가 소켓 주소로 채운 값)이다
- Gateway 는 연결 주소가 `gateway.trusted-proxies`(`GATEWAY_TRUSTED_PROXIES`, 주소·CIDR, 기본 루프백)일 때만 그 헤더의 맨 오른쪽 값을 쓰고, 아니면 지금처럼 연결 주소다. 브라우저가 Gateway 에 직접 `X-Forwarded-For` 를 보내도 무시된다
- 배포에서는 `GATEWAY_TRUSTED_PROXIES` 를 Next 대역으로, Next 앞에는 `X-Forwarded-For` 를 붙이는 프록시(Ingress)를 둔다. Next 를 프록시 없이 바로 열면 브라우저가 보낸 `X-Forwarded-For` 가 그대로 쓰여 IP 제한을 피할 수 있다(로그인 대입은 auth-service 가 아이디별로도 막는다)

## 검증

- CI `frontend`(PR 마다, `frontend/**`): Prettier, ESLint, typecheck(`next typegen && tsc`), Vitest, `next build`, Docker 이미지 빌드
- Vitest: 쿠키 속성·Set-Cookie 파싱·`sub` 읽기, auth 백엔드 호출(헤더·`X-Forwarded-For`·오류), 브라우저 IP 꺼내기, single-flight, `proxy`(통과·로그인 이동·refresh 성공·거절·백엔드 다운·지금 경로 헤더), 백엔드 401 뒤 renew(쿠키·`next` 검사·반복 방지), 입력 검증·open redirect, 폼
- Playwright(`frontend/e2e`, 로컬): 로그인 실패·성공·httpOnly·로그아웃, 가입 → 1,000P(auth.events). 백엔드 전체 + Next 를 띄우는 CI e2e 는 F-2 끝에 판단

## 결과

- 좋아지는 것: 토큰이 브라우저 JS 에 없다. 서비스는 바꾸지 않았다(Gateway 는 rate limit IP 만). 브라우저에 Gateway 를 열지 않아도 된다
- 대가: 화면 요청마다 Next 서버를 거친다(Next 가 BFF). refresh 묶기는 인스턴스 안에서만 된다. Next 가 refresh token 을 다루므로 Next 서버 로그에 쿠키·토큰을 남기지 않게 주의한다(`logError` 는 오류 메시지만)
- 한계: 브라우저 탭 여러 개가 서로 다른 Next 인스턴스로 동시에 refresh 하면 백엔드 유예(20초)에 기댄다. Secure 쿠키라 localhost 가 아닌 호스트를 http 로 보려면 `COOKIE_SECURE=false`
