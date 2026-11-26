import { expect, type Page, test } from "@playwright/test";

import { signUpFresh } from "./helpers";

// 포인트·결제 내역·알림. 주문 Saga 전체 + notification-service 가 떠 있어야 한다

async function order(page: Page, productId: number, points: number) {
  await page.goto(`/orders/new?productId=${productId}`);
  await page.getByLabel("사용할 포인트").fill(String(points));
  await page.getByRole("button", { name: "주문하기" }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
}

test("가입 축하 지급과 주문 사용이 포인트 내역에, 결제가 결제 내역에, 완료 알림이 알림에 남는다", async ({
  page,
}) => {
  await signUpFresh(page);

  await page.goto("/points");
  const history = page.getByRole("listitem");
  await expect(history).toHaveCount(1);
  await expect(history.first()).toContainText("가입 축하 포인트");
  await expect(history.first()).toContainText("+1,000P");

  await order(page, 2, 300);
  await expect(page.getByTestId("saga-state")).toHaveText("완료", { timeout: 20_000 });

  await page.goto("/points");
  await expect(page.getByText("700P", { exact: true })).toBeVisible();
  await expect(history).toHaveCount(2);
  // 최신순: 주문 사용이 먼저, 누르면 주문 상세
  await expect(history.first()).toContainText("주문에 사용");
  await expect(history.first()).toContainText("−300P");
  await history.first().getByRole("link").click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  const orderUrl = page.url();

  await page.goto("/payments");
  await expect(page.getByRole("listitem")).toHaveCount(1);
  await expect(page.getByRole("listitem").first()).toContainText("24,700원");
  await expect(page.getByRole("listitem").first()).toContainText("결제 완료");

  // 알림은 order.events 를 받아 비동기로 생긴다
  await expect(async () => {
    await page.goto("/notifications");
    await expect(page.getByRole("listitem").first()).toContainText("주문이 완료되었습니다", {
      timeout: 1_000,
    });
  }).toPass({ timeout: 20_000 });
  await page.getByRole("listitem").first().getByRole("link").click();
  await expect(page).toHaveURL(orderUrl);
});

test("포인트가 모자란 주문은 내역에 '사용 안 됨'으로 남고 잔액·결제는 그대로다", async ({
  page,
}) => {
  await signUpFresh(page);
  await order(page, 1, 5000);
  await expect(page.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });

  await page.goto("/points");
  await expect(page.getByText("1,000P", { exact: true })).toBeVisible();
  const first = page.getByRole("listitem").first();
  await expect(first).toContainText("사용 안 됨");
  await expect(first).toContainText("0P");

  await page.goto("/payments");
  await expect(page.getByText("아직 결제가 없습니다.")).toBeVisible();

  await expect(async () => {
    await page.goto("/notifications");
    await expect(page.getByRole("listitem").first()).toContainText("주문이 취소되었습니다", {
      timeout: 1_000,
    });
  }).toPass({ timeout: 20_000 });
});
