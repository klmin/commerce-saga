import { defineConfig, devices } from "@playwright/test";

// 이미 떠 있는 화면을 대상으로 돈다 (BFF 로그인 → SAS 로그인 화면 → 돌아오기).
//   로컬: 인프라 + 서비스 + Gateway(:8000) + auth-service(:8086) + 이 화면(pnpm dev 또는 build 뒤 preview, :5173). README
//   CI: .github/workflows/browser-e2e.yml
export default defineConfig({
  testDir: "./e2e",
  // 실제 백엔드를 쓰는 시나리오라 순서대로 돈다: 재고·시드 사용자 같은 공유 데이터를 바꾼다
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
