import { expect, type Page, test } from "@playwright/test";

import { SEED_PRODUCT, signUpFresh } from "./helpers";

// 상품 카드·상세 (후속 9-5b, ADR 0021). 시드 상품은 카테고리·시드 이미지(SVG)가 있다
const products = (page: Page) =>
  page.getByRole("list", { name: "상품 목록" }).getByRole("listitem");

test("카드에서 상세로 가서 수량을 정해 장바구니에 담거나 바로 주문한다", async ({ page }) => {
  await signUpFresh(page);
  const card = products(page).filter({ hasText: "무선 마우스" });
  await expect(card.getByText("디지털")).toBeVisible();
  await card.getByRole("link", { name: "무선 마우스" }).click();
  await expect(page).toHaveURL(new RegExp(`/products/${SEED_PRODUCT.mouse}$`));

  await expect(page.getByRole("heading", { level: 1, name: "무선 마우스" })).toBeVisible();
  await expect(page.getByTestId("detail-price")).toHaveText(/^[\d,]+원$/);
  await expect(page.getByTestId("detail-stock")).not.toHaveText("재고 확인 중");
  // 시드 이미지가 Gateway /api 로 실제로 그려진다
  const image = page.getByRole("img", { name: "무선 마우스" });
  await expect(image).toHaveAttribute("src", /^\/api\/products\/[0-9a-f-]{36}\/images\//);
  await expect
    .poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth))
    .toBeGreaterThan(0);

  // 잘못된 수량은 화면에서 막는다
  const quantity = page.getByLabel("수량");
  await quantity.fill("0");
  await page.getByRole("button", { name: "장바구니 담기" }).click();
  await expect(page.getByRole("alert")).toContainText("수량은 1 ~ 1,000");

  await quantity.fill("2");
  await page.getByRole("button", { name: "장바구니 담기" }).click();
  await expect(page).toHaveURL(/\/cart$/);
  await expect(page.getByLabel("무선 마우스 수량")).toHaveValue("2");
  await expect(
    page.getByRole("banner").getByRole("link", { name: "장바구니 (1개)" }),
  ).toBeVisible();

  // 바로 주문: 주문 화면이 그 수량으로 열린다
  await page.goto(`/products/${SEED_PRODUCT.mouse}`);
  await page.getByLabel("수량").fill("3");
  await page.getByRole("button", { name: "바로 주문" }).click();
  await expect(page).toHaveURL(
    new RegExp(`/orders/new[?]productId=${SEED_PRODUCT.mouse}&quantity=3$`),
  );
  await expect(page.getByLabel("무선 마우스")).toHaveValue("3");
});

test("품절 상품 상세는 품절로 보이고, 없는 상품은 찾을 수 없다", async ({ page }) => {
  await signUpFresh(page);
  await page.goto(`/products/${SEED_PRODUCT.monitorArm}`);
  await expect(page.getByRole("heading", { level: 1, name: "단종 모니터암" })).toBeVisible();
  await expect(page.getByTestId("detail-stock")).toHaveText("품절");
  await expect(page.getByText("품절이어도 주문할 수 있습니다.")).toBeVisible();

  await page.goto("/products/01a20000-0000-7000-8000-000000000000");
  await expect(page.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
  await page.goto("/products/not-a-uuid");
  await expect(page.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
});
