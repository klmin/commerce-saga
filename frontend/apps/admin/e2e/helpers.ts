import { expect, type Page } from "@playwright/test";

// 개발용 시드 사용자·관리자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
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

/** 사용자 화면 주소 (관리자가 바꾼 것이 사용자 쪽에 어떻게 보이는지 확인할 때) */
export const WEB_URL = process.env.E2E_WEB_URL ?? "http://localhost:5173";

/**
 * 화면을 열면 세션이 없어 Gateway 로그인(관리자 화면이라 gateway-admin) → SAS 로그인 화면(/signin)으로 간다.
 * 로그인하면 열려던 화면으로 돌아온다
 */
export async function login(page: Page, loginId: string, path = "/") {
  await page.goto(path);
  await expect(page).toHaveURL(/\/signin/);
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(DEV_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  // 헤더의 로그아웃 버튼 (관리자가 아니면 본문에도 하나 더 있다)
  await expect(page.getByRole("banner").getByRole("button", { name: "로그아웃" })).toBeVisible();
}
