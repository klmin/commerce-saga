import { expect, test } from "@playwright/test";

import { login, SEED_PRODUCT, signUpFresh } from "./helpers";

// 주문 Saga 전체(order·inventory·point·payment + Kafka)가 떠 있어야 한다. 가입한 새 사용자로 돌아 시드 잔액에 기대지 않는다

test("포인트를 써서 주문하면 Saga 가 재고 → 포인트 → 결제 → 완료로 진행된다", async ({ page }) => {
  await signUpFresh(page);
  await page.goto(`/orders/new?productId=${SEED_PRODUCT.mouse}`);
  await page.getByLabel("사용할 포인트").fill("300");
  await expect(page.getByText("24,700원")).toBeVisible();
  await page.getByRole("button", { name: "주문하기" }).click();

  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  // 진행 중이면 화면이 스스로 갱신된다
  await expect(page.getByTestId("saga-state")).toHaveText("완료", { timeout: 20_000 });
  const steps = page.getByRole("list", { name: "Saga 단계" }).getByRole("listitem");
  await expect(steps).toHaveCount(4);
  for (const step of await steps.all()) {
    await expect(step).toHaveAttribute("data-status", "done");
  }
  await expect(page.getByText("POINT_USED")).toBeVisible();

  await page.goto("/");
  await expect(page.getByText("700P")).toBeVisible();
});

test("품절 상품은 재고 예약에서 실패하고 되돌릴 것 없이 취소된다", async ({ page }) => {
  await signUpFresh(page);
  await page.goto(`/orders/new?productId=${SEED_PRODUCT.monitorArm}`);
  await page.getByRole("button", { name: "주문하기" }).click();

  await expect(page.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });
  await expect(page.getByText("취소 사유: insufficient stock")).toBeVisible();
  await expect(page.getByText("STOCK_RESERVATION_FAILED")).toBeVisible();
  await expect(page.getByText("STOCK_RELEASED")).toHaveCount(0);
});

test("포인트가 모자라면 포인트 단계에서 실패하고 재고를 되돌린다 (보상)", async ({ page }) => {
  await signUpFresh(page);
  await page.goto(`/orders/new?productId=${SEED_PRODUCT.keyboard}`);
  await page.getByLabel("사용할 포인트").fill("5000");
  await page.getByRole("button", { name: "주문하기" }).click();

  await expect(page.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });
  const stock = page.getByRole("list", { name: "Saga 단계" }).getByRole("listitem").first();
  await expect(stock).toHaveAttribute("data-status", "undone");
  await expect(page.getByText("STOCK_RELEASED")).toBeVisible();
  await page.goto("/");
  await expect(page.getByText("1,000P")).toBeVisible();
});

test("주문 버튼을 여러 번 눌러도 주문은 한 건이다 (Idempotency-Key, 시나리오 10)", async ({
  page,
}) => {
  await signUpFresh(page);
  await page.goto(`/orders/new?productId=${SEED_PRODUCT.hub}`);
  const submit = page.getByRole("button", { name: "주문하기" });
  // 주문 폼이 그려질 때까지 기다린다(재고를 읽는 동안은 자리 표시만 있다). 헤더의 로그아웃 폼과 헷갈리지 않게
  // 주문하기 버튼이 든 폼을 고른다. 응답 전에 연달아 제출한다 (버튼이 잠기기 전에 같은 폼을 세 번 보낸다)
  await expect(submit).toBeVisible();
  await page.locator("form", { has: submit }).evaluate((form: HTMLFormElement) => {
    form.requestSubmit();
    form.requestSubmit();
    form.requestSubmit();
  });
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  await expect(submit).toHaveCount(0);

  await page.goto("/orders");
  await expect(page.getByRole("link", { name: /USB-C 허브/ })).toHaveCount(1);
});

test("남의 주문은 볼 수 없다 (소유권, 없는 주문과 같은 화면)", async ({ page, browser }) => {
  await signUpFresh(page);
  await page.goto(`/orders/new?productId=${SEED_PRODUCT.keyboard}`);
  await page.getByRole("button", { name: "주문하기" }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  const mine = page.url();

  // 다른 브라우저(쿠키가 따로)에서 다른 사용자로
  const other = await browser.newPage({ baseURL: test.info().project.use.baseURL });
  await login(other, "/orders", "user2");

  await other.goto(mine);
  await expect(other.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
  await other.goto("/orders/01a20000-0000-7000-8000-000000000000");
  await expect(other.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
  await other.close();
});

test("장바구니에 담아 주문하면 주문한 상품이 장바구니에서 빠진다 (후속 4-1c)", async ({ page }) => {
  await signUpFresh(page);
  await page.getByRole("button", { name: "무선 마우스 장바구니 담기" }).click();
  await expect(page).toHaveURL(/\/cart$/);
  const items = page.getByRole("list", { name: "장바구니 상품" }).getByRole("listitem");
  await expect(items).toHaveCount(1);
  await expect(items.first()).toContainText("무선 마우스");
  await expect(page.getByText("25,000원").first()).toBeVisible();

  await page.getByRole("button", { name: "주문하기" }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId("saga-state")).toHaveText("완료", { timeout: 20_000 });

  await page.goto("/cart");
  await expect(page.getByText("장바구니가 비어 있습니다.")).toBeVisible();
});
