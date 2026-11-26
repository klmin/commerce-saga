import { expect, type Page, test } from "@playwright/test";

// 관리자 화면 (F-2b). 결제 장애 주입은 모든 주문에 걸리므로 끝에 반드시 정상 승인으로 되돌린다.
// 개발용 시드 관리자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

async function login(page: Page, loginId: string, password: string, next = "/") {
  await page.goto(`/login?next=${encodeURIComponent(next)}`);
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(new RegExp(`${next.replace(/\//g, "\\/")}$`));
}

async function setFault(page: Page, label: RegExp) {
  await page.goto("/admin");
  await page.getByLabel(label).check();
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("status").filter({ hasText: "저장했습니다" })).toBeVisible();
}

test("관리자가 결제 거절을 주입하면 주문이 결제 단계에서 실패하고 포인트·재고를 되돌린다", async ({
  page,
  browser,
}) => {
  await login(page, "admin", DEV_PASSWORD);
  await expect(page.getByRole("link", { name: "관리자" })).toBeVisible();
  try {
    await setFault(page, /거절/);
    // 새로 연 화면도 저장한 값을 보여 준다
    await page.reload();
    await expect(page.getByLabel(/거절/)).toBeChecked();

    const context = await browser.newContext();
    const user = await context.newPage();
    const loginId = `e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
    await user.goto("/signup");
    await user.getByLabel("아이디").fill(loginId);
    await user.getByLabel("비밀번호", { exact: true }).fill("e2e-password");
    await user.getByLabel("비밀번호 확인").fill("e2e-password");
    await user.getByRole("button", { name: "가입하기" }).click();
    await expect(user).toHaveURL(/\/$/);
    await expect(async () => {
      await user.reload();
      await expect(user.getByText("1,000P", { exact: true })).toBeVisible({ timeout: 1_000 });
    }).toPass({ timeout: 20_000 });

    await user.goto("/orders/new?productId=2");
    await user.getByLabel("사용할 포인트").fill("300");
    await user.getByRole("button", { name: "주문하기" }).click();
    await expect(user.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });
    await expect(user.getByText("PAYMENT_FAILED")).toBeVisible();
    await expect(user.getByText("POINT_REFUNDED")).toBeVisible();
    await expect(user.getByText("STOCK_RELEASED")).toBeVisible();

    await user.goto("/payments");
    await expect(user.getByRole("listitem").first()).toContainText("결제 거절");
    await user.goto("/points");
    await expect(user.getByText("1,000P", { exact: true })).toBeVisible();
    await expect(user.getByRole("listitem").first()).toContainText("환불됨");
    await context.close();
  } finally {
    await setFault(page, /정상 승인/);
  }
  await expect(page.getByLabel(/정상 승인/)).toBeChecked();
});

test("관리자가 아니면 메뉴가 없고 /admin 은 없는 화면이다", async ({ page }) => {
  await login(page, "user1", DEV_PASSWORD);
  await expect(page.getByRole("link", { name: "관리자" })).toHaveCount(0);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
});
