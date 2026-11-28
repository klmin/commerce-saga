import { expect, type Page, test } from "@playwright/test";

import { login, SEED_PRODUCT, WEB_URL } from "./helpers";

// 결제 장애 주입은 모든 주문에 걸리므로 끝에 반드시 정상 승인으로 되돌린다

async function setFault(page: Page, label: RegExp) {
  await page.goto("/");
  await page.getByLabel(label).check();
  await page.getByRole("button", { name: "저장" }).click();
  await expect(page.getByRole("status").filter({ hasText: "저장했습니다" })).toBeVisible();
}

test("관리자가 결제 거절을 주입하면 사용자 주문이 결제 단계에서 실패하고 포인트·재고를 되돌린다", async ({
  page,
  browser,
}) => {
  await login(page, "admin");
  try {
    await setFault(page, /거절/);
    // 새로 연 화면도 저장한 값을 보여 준다
    await page.reload();
    await expect(page.getByLabel(/거절/)).toBeChecked();

    // 다른 브라우저(쿠키가 따로)에서 새 사용자로 가입해 사용자 화면에서 주문
    const context = await browser.newContext({ baseURL: WEB_URL });
    const user = await context.newPage();
    await user.goto("/");
    await expect(user).toHaveURL(/\/signin/);
    await user.getByRole("link", { name: "회원가입" }).click();
    // 로그인·가입 화면 모두 "아이디" 칸이 있어, 가입 화면으로 바뀌기 전에 입력하면 로그인 화면 칸에 들어간다
    await expect(user.getByRole("heading", { name: "회원가입" })).toBeVisible();
    await user
      .getByLabel("아이디")
      .fill(`e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`);
    await user.getByLabel("비밀번호").fill("e2e-password");
    await user.getByRole("button", { name: "가입하고 로그인" }).click();
    await expect(async () => {
      await user.reload();
      await expect(user.getByText("1,000P", { exact: true })).toBeVisible({ timeout: 1_000 });
    }).toPass({ timeout: 20_000 });

    await user.goto(`/orders/new?productId=${SEED_PRODUCT.mouse}`);
    await user.getByLabel("사용할 포인트").fill("300");
    await user.getByRole("button", { name: "주문하기" }).click();
    await expect(user.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });
    await expect(user.getByText("PAYMENT_FAILED")).toBeVisible();
    await expect(user.getByText("POINT_REFUNDED")).toBeVisible();
    await expect(user.getByText("STOCK_RELEASED")).toBeVisible();

    await user.goto("/payments");
    await expect(user.getByRole("main").getByRole("listitem").first()).toContainText("결제 거절");
    await user.goto("/points");
    await expect(user.getByText("1,000P", { exact: true })).toBeVisible();
    await expect(user.getByRole("main").getByRole("listitem").first()).toContainText("환불됨");
    await context.close();
  } finally {
    await setFault(page, /정상 승인/);
  }
  await expect(page.getByLabel(/정상 승인/)).toBeChecked();
});

test("관리자가 아니면 관리자 화면을 볼 수 없다", async ({ page }) => {
  await login(page, "user1");
  await expect(page.getByRole("heading", { name: "관리자만 볼 수 있는 화면입니다" })).toBeVisible();
  await expect(page.getByText("결제 장애 주입")).toHaveCount(0);
});
