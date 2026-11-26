import { type Browser, expect, type Page, test } from "@playwright/test";

// 로그인 기기 관리 (F-2b). auth-service + gateway 만 있으면 된다. 새로 가입한 사용자로 돈다

async function signUp(page: Page): Promise<string> {
  const loginId = `e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  await page.goto("/signup");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호", { exact: true }).fill("e2e-password");
  await page.getByLabel("비밀번호 확인").fill("e2e-password");
  await page.getByRole("button", { name: "가입하기" }).click();
  await expect(page).toHaveURL(/\/$/);
  return loginId;
}

// 쿠키를 나눠 쓰지 않는 새 브라우저 = 다른 기기
async function loginOnAnotherDevice(browser: Browser, loginId: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/login?next=%2Fsessions");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill("e2e-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  return page;
}

test("다른 기기를 로그아웃하면 그 기기는 refresh 가 거절돼 다시 로그인해야 한다", async ({
  page,
  browser,
}) => {
  const loginId = await signUp(page);
  const other = await loginOnAnotherDevice(browser, loginId);

  await page.getByRole("link", { name: /^사용자 #\d+$/ }).click();
  await expect(page).toHaveURL(/\/sessions$/);
  const devices = page.getByRole("listitem");
  await expect(devices).toHaveCount(2);
  // 이 기기가 맨 위, 로그아웃 버튼은 다른 기기에만
  await expect(devices.first()).toContainText("이 기기");
  await expect(devices.first().getByRole("button")).toHaveCount(0);

  await devices.nth(1).getByRole("button", { name: "로그아웃" }).click();
  await expect(devices).toHaveCount(1);
  await expect(devices.first()).toContainText("이 기기");

  // 다른 기기: access token 이 끝난 것처럼 access 쿠키만 지우면 proxy 가 refresh 하다 거절돼 로그인 화면으로
  await other.context().clearCookies({ name: "cs_access" });
  await other.goto("/orders");
  await expect(other).toHaveURL(/\/login\?next=%2Forders$/);
  await other.context().close();

  // 이 기기는 그대로
  await page.goto("/orders");
  await expect(page).toHaveURL(/\/orders$/);
});

test("모든 기기에서 로그아웃하면 이 기기도 로그인 화면으로", async ({ page, browser }) => {
  const loginId = await signUp(page);
  const other = await loginOnAnotherDevice(browser, loginId);

  await page.goto("/sessions");
  await expect(page.getByRole("listitem")).toHaveCount(2);
  await page.getByRole("button", { name: "모든 기기에서 로그아웃" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto("/orders");
  await expect(page).toHaveURL(/\/login\?next=%2Forders$/);

  await other.context().clearCookies({ name: "cs_access" });
  await other.goto("/orders");
  await expect(other).toHaveURL(/\/login\?next=%2Forders$/);
  await other.context().close();
});
