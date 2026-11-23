# frontend

Next.js 16 (App Router, Cache Components) / React 19 / TypeScript / Tailwind 4 / shadcn/ui. 설계는 [ADR 0011](../docs/adr/0011-frontend-nextjs-bff-session.md).

브라우저는 Next 만 본다. Next 서버가 로그인 토큰을 httpOnly 쿠키(`cs_access`, `cs_refresh`)로 들고 Gateway(`BACKEND_URL`, 기본 http://localhost:8000)를 부른다. access token 이 만료되면 `src/proxy.ts` 가 화면 요청 전에 refresh 한다.

## 실행

```bash
# 루트: 인프라 + 백엔드 (README). 화면에 필요한 최소: auth-service(8086), point-service(8083), gateway(8000)
docker compose -f infra/docker-compose.yml up -d --wait

# frontend
cp .env.example .env.local   # 기본값 그대로면 없어도 된다
npm ci
npm run dev -- --port 3001   # 3000 은 Grafana 가 쓴다
```

개발용 계정은 루트 README 의 시드 사용자(`user1`~`user4`). 새로 가입하면 몇 초 안에 가입 축하 1,000P 가 생긴다(시나리오 13).

| 환경 변수       | 기본값                  | 설명                                                                                                  |
| --------------- | ----------------------- | ----------------------------------------------------------------------------------------------------- |
| `BACKEND_URL`   | `http://localhost:8000` | Gateway. Next 서버만 부른다                                                                           |
| `COOKIE_SECURE` | `true`                  | 세션 쿠키 Secure. 브라우저는 localhost 에서는 http 여도 받는다. 다른 호스트를 http 로 볼 때만 `false` |

## 스크립트

- `npm run dev` - 개발 서버
- `npm run build` - 프로덕션 빌드
- `npm run lint` - ESLint (Next 16부터 `next lint` 없음)
- `npm run format` / `npm run format:check` - Prettier (Tailwind 클래스 정렬 포함)
- `npm test` - Vitest
- `npm run test:e2e` - Playwright (앱과 백엔드가 떠 있어야 함)
- `npm run typecheck` - `next typegen && tsc --noEmit`

CI(`.github/workflows/frontend.yml`)는 format·lint·typecheck·test·build·Docker 이미지 빌드를 돈다.

## 구조

```
src/proxy.ts              화면 요청 전 세션 확인·refresh (Next 16 의 Middleware)
src/lib/auth/             세션 쿠키, auth-service 호출(login·refresh·logout·signup), refresh 묶기, 입력 검증
src/lib/api/              API 클라이언트(Problem Details → ApiError), authedRequest(Bearer), 화면별 API + zod 스키마
src/app/                  라우트. Server Action 은 *-actions.ts, 화면 컴포넌트는 라우트 옆에
src/components/ui/        shadcn/ui
```

API 응답 타입은 손으로 쓴 zod 스키마로 검증한다(OpenAPI 문서가 없다).

## 테스트

Vitest + Testing Library. 파일은 대상 옆에 `*.test.ts(x)`로 둔다.
async 서버 컴포넌트는 Vitest가 지원하지 않아서 E2E로 확인한다.

E2E는 Playwright로 `e2e/`에 둔다. 떠 있는 앱을 대상으로 돈다.

```bash
npx playwright install chromium            # 처음 한 번
E2E_BASE_URL=http://localhost:3001 npm run test:e2e
```

## UI 컴포넌트

[shadcn/ui](https://ui.shadcn.com)(Radix, new-york 스타일)를 쓴다. 컴포넌트는 패키지가 아니라 코드로 `src/components/ui/`에 들어 있어서 직접 고쳐도 된다.

```bash
npx shadcn@latest add dialog
```

## 주의

Next.js 16은 학습 데이터와 다른 점이 많다. 코드를 쓰기 전에 `node_modules/next/dist/docs/`의 해당 가이드를 먼저 읽는다 ([AGENTS.md](AGENTS.md)).
