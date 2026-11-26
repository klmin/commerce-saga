import { expect, test } from "@playwright/test";

// 개발용 시드 사용자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

test("로그인하지 않으면 로그인 화면으로, 로그인하면 돌아가려던 화면과 내 포인트", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);

  await page.getByLabel("아이디").fill("user1");
  await page.getByLabel("비밀번호").fill("not-the-password");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  // 실패 응답 뒤 React 가 폼을 초기화한다. 그 직후 입력이 지워지지 않게 새로 연 화면에서 다시 로그인한다
  await expect(page.getByLabel("아이디")).toHaveValue("user1");

  await page.goto("/login?next=%2F");
  await page.getByLabel("아이디").fill("user1");
  await page.getByLabel("비밀번호").fill(DEV_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByText("내 포인트")).toBeVisible();
  await expect(page.getByText(/^[\d,]+P$/)).toBeVisible();

  // 토큰은 httpOnly 쿠키라 페이지 JS 가 볼 수 없다
  expect(await page.evaluate(() => document.cookie)).toBe("");

  await page.getByRole("button", { name: "로그아웃" }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test("가입하면 바로 로그인되고 가입 축하 포인트 1,000P 가 생긴다 (auth.events → point-service)", async ({
  page,
}) => {
  const loginId = `e2e_${Date.now().toString(36)}`;
  await page.goto("/signup");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호", { exact: true }).fill("e2e-password");
  await page.getByLabel("비밀번호 확인").fill("e2e-password");
  await page.getByRole("button", { name: "가입하기" }).click();

  // 포인트 계정은 이벤트로 몇 초 안에 생긴다. 그 사이에는 "만드는 중" 안내
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("1,000P")).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 20_000 });
});
