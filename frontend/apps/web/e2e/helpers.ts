import { expect, type Page } from "@playwright/test";

// 개발용 시드 사용자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
export const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

/** product-service 시드 상품의 고정 id (UUID, ADR 0018). 서비스 사이 시드와 같다 */
export const SEED_PRODUCT = {
  /** 무선 키보드 */
  keyboard: "01900000-0000-7000-8000-000000000001",
  /** 무선 마우스 */
  mouse: "01900000-0000-7000-8000-000000000002",
  /** USB-C 허브 */
  hub: "01900000-0000-7000-8000-000000000003",
  /** 한정판 키캡 (재고 1) */
  keycap: "01900000-0000-7000-8000-000000000004",
  /** 단종 모니터암 (재고 0) */
  monitorArm: "01900000-0000-7000-8000-000000000005",
} as const;

/**
 * 화면을 열면 세션이 없어 Gateway 로그인 → SAS 로그인 화면(/signin, 인증 화면 SPA)으로 간다 (ADR 0017).
 * 로그인하면 돌아가려던 화면으로 돌아온다
 */
export async function login(page: Page, path = "/", loginId = "user1", password = DEV_PASSWORD) {
  await page.goto(path);
  await expect(page).toHaveURL(/\/signin/);
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByText(`사용자 #`)).toBeVisible();
}

/**
 * 새 사용자로 가입하고 로그인된 채 홈으로 돌아온다. 가입 축하 포인트 계정이 생길 때까지 기다린다 (시나리오 13).
 * 다른 시나리오의 기록·잔액과 섞이지 않게 새 사용자로 돈다
 */
export async function signUpFresh(page: Page): Promise<string> {
  const loginId = `e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  await page.goto("/");
  await expect(page).toHaveURL(/\/signin/);
  await page.getByRole("link", { name: "회원가입" }).click();
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill("e2e-password");
  await page.getByRole("button", { name: "가입하고 로그인" }).click();
  await expect(page.getByText(`사용자 #`)).toBeVisible();
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("1,000P", { exact: true })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 20_000 });
  return loginId;
}
