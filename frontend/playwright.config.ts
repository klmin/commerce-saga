import { defineConfig, devices } from "@playwright/test";

// 이미 떠 있는 앱을 대상으로 돈다.
//   로컬: 인프라 + auth·point·gateway(:8000), 주문 시나리오(orders.spec)는 order·inventory·payment 도 띄우고 npm run dev (README)
//   CI 에는 아직 넣지 않았다 (F-2 끝에 판단)
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
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    // 로그인 쿠키는 Secure 다. 브라우저는 localhost 에서는 http 여도 받는다
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
