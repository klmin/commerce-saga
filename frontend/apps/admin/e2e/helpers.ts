import { expect, type Page } from "@playwright/test";

// 개발용 시드 사용자·관리자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
export const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

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
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
}
