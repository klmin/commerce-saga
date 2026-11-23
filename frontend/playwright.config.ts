import { defineConfig, devices } from "@playwright/test";

// 이미 떠 있는 앱을 대상으로 돈다.
//   로컬: 인프라 + auth-service·point-service·gateway(:8000) 를 띄우고 npm run dev (README)
//   CI 에는 아직 넣지 않았다 (F-2 끝에 판단)
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
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
