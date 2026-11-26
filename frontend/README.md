# frontend

화면 SPA workspace (pnpm). 설계는 [ADR 0017](../docs/adr/0017-auth-sas-gateway-bff-spa.md)·[인증 재구성 설계](../docs/auth-sas-bff-design.md).

| 경로 | 내용 |
|---|---|
| `apps/web` | 사용자 화면 (Vite + React 19 + TypeScript + Tailwind 4 + shadcn/ui, React Router, TanStack Query). 5-3a |
| `apps/admin` | 관리자 화면: 상품 관리·관리 이력, 결제 장애 주입(로컬 :5174, 로그인 클라이언트 `gateway-admin`). 5-5 |
| `apps/auth` | 인증 화면: SAS 의 로그인(`/signin`)·회원가입(`/register`)·로그인 기기(`/devices`, 5-6). 폼은 auth-service 로 그대로 POST(`login`·`signup`, CSRF 는 `GET csrf` 본문). 로컬 :5175 = 로컬 issuer. 5-4 |
| `packages/bff` | 화면들이 Gateway(BFF)를 부르는 공용 코드: `/api` 호출(CSRF·401 → 로그인), `<base href>` 앞 경로, `/api/me`, 로그인 확인(`SessionGate`), 로그아웃 폼 |
| `packages/ui` | 화면들이 같이 쓰는 shadcn/ui 컴포넌트와 테마. 빌드 없이 소스를 내보내고 각 앱의 Vite 가 함께 묶는다 |

## 구조

- 화면은 **토큰을 모른다.** 같은 출처의 `/api/**` 를 세션 쿠키(`GW_SESSION`, httpOnly)로 부르고, Gateway(BFF)가 세션의 토큰을 `Authorization: Bearer` 로 붙여 서비스로 넘긴다
- 로그인: API 가 401 이면 `/oauth2/authorization/auto?return_to=<지금 경로>` 로 화면을 옮긴다 → Gateway → SAS 로그인 화면 → 돌아오기. SSO 세션이 남아 있으면 화면 없이 바로 돌아온다
- 상태를 바꾸는 요청은 쿠키 `XSRF-TOKEN` 값을 헤더 `X-XSRF-TOKEN` 으로 보낸다(Gateway CSRF). 로그아웃은 `/logout` 폼 POST(`_csrf`, `return_to`)
- 지금 사용자는 `GET /api/me`(Gateway 가 세션으로 답함: `userId`, `roles`)
- **앱은 항상 루트(`/`)에서 돈다고 생각한다.** 빌드는 상대 경로(`base: "./"`), 앞 경로는 `index.html` 의 `<base href>` 로 받는다(경로 방식 배포에서 nginx 가 `BASE_PATH` 로 바꿈, 5-3b). Gateway 경로(`/api`, `/oauth2/authorization`, `/logout`)는 루트 절대 경로로 부르고, 화면 링크는 라우터(`basename` = `<base>`)로
- 화면 경로는 Gateway·SAS 경로(설계 문서 7-1)를 쓰지 않는다

## 실행 (로컬 개발)

```bash
# 루트: 인프라 + 백엔드 (루트 README). 화면에 필요한 최소: auth-service(8086), gateway(8000), 쓰는 서비스들
docker compose -f infra/docker-compose.yml up -d --wait

# frontend (Node 22.22+ / 24.15+ / 26, pnpm 은 package.json 의 packageManager 버전)
npm install -g pnpm@12.23.0   # 또는 corepack enable
pnpm install
pnpm dev:web                  # 사용자 화면 http://localhost:5173
pnpm dev:admin                # 관리자 화면 http://localhost:5174 (관리자: 시드 admin)
pnpm dev:auth                 # 인증 화면 http://localhost:5175 (로그인할 때 꼭 필요: 로컬 issuer 주소)
```

Vite 개발 서버가 설계 문서 7-1 "화면 쪽" 경로(`/api`, `/oauth2/authorization`, `/login/oauth2/code`, `/logout`)를 Gateway(`GATEWAY_URL`, 기본 http://localhost:8000)로 넘긴다. 원래 `Host`(`localhost:5173`)를 그대로 넘겨야 Gateway 가 이 화면의 로그인 클라이언트(`gateway-web`)와 돌아올 주소를 고른다(`changeOrigin: false`). 로그인 화면은 인증 화면 개발 서버(`AUTH_ISSUER`, 로컬 기본 http://localhost:5175)의 `/signin` 이다. 인증 화면의 Vite 는 7-1 "인증 쪽" 의 auth-service 경로(`/oauth2`, `/.well-known`, `/userinfo`, `/connect/logout`, `/login`, `/signup`, `/csrf`, `/sessions`)를 auth-service(`AUTH_SERVICE_URL`, 기본 http://localhost:8086)로 넘기고, 역시 원래 `Host` 를 넘겨 SAS 가 만드는 주소(로그인 화면·돌아갈 인가 요청)가 :5175 가 되게 한다.

인증 화면은 정적 파일이라 CSRF 토큰을 만들지 않는다: 화면을 열 때 `GET csrf`(SSO 세션 `AUTH_SESSION` 에 둔 토큰)를 받아 폼의 숨은 필드로 넣고, 로그인·가입은 fetch 가 아니라 **폼 POST** 다(auth-service 의 302 를 브라우저가 화면 이동으로 따라가야 인가 흐름이 이어진다). 주소는 `<base>` 기준 상대 경로(`login`·`signup`·`csrf`)라 경로 방식(`/auth/…`)에서도 그대로 맞는다.

개발용 계정은 루트 README 의 시드 사용자(`user1`~`user4`). 새로 가입하면 몇 초 안에 가입 축하 1,000P 가 생긴다(시나리오 13).

## 명령 (frontend 에서)

- `pnpm dev:web` / `pnpm dev:admin` / `pnpm dev:auth` - 사용자·관리자·인증 화면 개발 서버
- `pnpm build` - 모든 앱 프로덕션 빌드 (`apps/*/dist`)
- `pnpm lint` - ESLint (workspace 전체)
- `pnpm format` / `pnpm format:check` - Prettier (Tailwind 클래스 정렬 포함)
- `pnpm typecheck` - `tsc --noEmit` (앱·패키지마다)
- `pnpm test` - Vitest (앱마다)
- `pnpm --filter web test:e2e`·`pnpm --filter admin test:e2e` - Playwright. 관리자 e2e 는 사용자 화면(`E2E_WEB_URL`, 기본 :5173)도 쓴다. 백엔드와 화면이 떠 있어야 한다(기본 http://localhost:5173, 다른 주소는 `E2E_BASE_URL`). 프로덕션 빌드로 보려면 `pnpm --filter web build && pnpm --filter web preview`(같은 프록시, :5173)

## 이미지 (nginx)

```bash
docker build --build-arg APP=web -t commerce-saga/web frontend      # 저장소 루트에서
docker run -p 8080:8080 -e BASE_PATH=/admin/ commerce-saga/admin      # 경로 방식이면 앞 경로
```

정적 파일만 낸다(`nginx/default.conf`: 화면 경로는 `index.html`, `/healthz`). `/api` 등 Gateway 경로와 앞 경로 떼기는 앞단(k8s edge·compose Caddy)이 한다. `BASE_PATH` 는 / 로 시작하고 끝나야 하고 틀리면 기동하지 않는다(`nginx/40-base-path.sh`). k8s 는 `deploy/helm/commerce-saga/templates/screens.yaml`.

CI(`.github/workflows/frontend.yml` 의 `web`)는 format·lint·typecheck·test·build 를, `browser-e2e.yml` 은 백엔드 전체 + `vite preview` 로 `apps/web/e2e` 를 돈다.
