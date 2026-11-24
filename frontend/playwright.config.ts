import { defineConfig, devices } from "@playwright/test";

// 이미 떠 있는 앱을 대상으로 돈다.
//   로컬: 인프라 + 서비스 7개(:8000 gateway) + npm run dev (README). 기기 관리(sessions.spec)는 auth·gateway 만 있으면 된다
//   CI: .github/workflows/browser-e2e.yml (next build + next start)
//   관리자 시나리오(admin.spec)는 결제 장애를 잠깐 주입했다가 정상 승인으로 되돌린다. 다른 사람이 쓰는 환경에서는 돌리지 않는다
export default defineConfig({
  testDir: "./e2e",
  // 실제 백엔드를 쓰는 시나리오라 순서대로 돈다: 가입·로그인이 한 IP 로 몰리면 Gateway /auth rate limit(초당 5) 에 걸리고,
  // 재고·시드 사용자 같은 공유 데이터를 바꾼다
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3500",
    // 로그인 쿠키는 Secure 다. 브라우저는 localhost 에서는 http 여도 받는다
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
