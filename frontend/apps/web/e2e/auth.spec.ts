import { expect, test } from "@playwright/test";

import { DEV_PASSWORD, login } from "./helpers";

test("로그인하지 않으면 SAS 로그인 화면으로, 로그인하면 가려던 화면으로 돌아온다", async ({
  page,
}) => {
  await page.goto("/points");
  await expect(page).toHaveURL(/\/signin/);

  await page.getByLabel("아이디").fill("user1");
  await page.getByLabel("비밀번호").fill("not-the-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/signin\?error/);
  await expect(page.getByText("아이디 또는 비밀번호가 맞지 않습니다.")).toBeVisible();

  await page.getByLabel("아이디").fill("user1");
  await page.getByLabel("비밀번호").fill(DEV_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/:5173\/points$|\/points$/);
  await expect(page.getByText("포인트 내역")).toBeVisible();

  // 세션 쿠키(GW_SESSION)는 httpOnly 라 페이지 JS 가 볼 수 없다. 보이는 것은 CSRF 토큰뿐이다
  const cookies = await page.evaluate(() => document.cookie);
  expect(cookies).not.toContain("GW_SESSION");
  expect(cookies).not.toContain("AUTH_SESSION");
});

test("로그아웃하면 SSO 세션까지 끝나 다시 로그인 화면이 나온다", async ({ page }) => {
  await login(page);
  await page.getByRole("button", { name: "로그아웃" }).click();
  // Gateway 세션 삭제 → SAS OIDC 로그아웃 → 화면 홈 → 세션이 없어 다시 로그인 시작 → SAS 세션도 없어 로그인 화면
  await expect(page).toHaveURL(/\/signin/);
  await expect(page.getByLabel("아이디")).toBeVisible();
});

test("가입하면 바로 로그인되고 가입 축하 포인트 1,000P 가 생긴다 (auth.events → point-service)", async ({
  page,
}) => {
  const loginId = `e2e_${Date.now().toString(36)}`;
  await page.goto("/");
  await page.getByRole("link", { name: "회원가입" }).click();
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill("e2e-password");
  await page.getByRole("button", { name: "가입하고 로그인" }).click();

  // 포인트 계정은 이벤트로 몇 초 안에 생긴다. 그 사이에는 "만드는 중" 안내
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("1,000P", { exact: true })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 20_000 });
});
