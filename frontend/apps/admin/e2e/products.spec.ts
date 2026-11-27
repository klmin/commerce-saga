import { expect, type Page, test } from "@playwright/test";

import { login, SEED_PRODUCT } from "./helpers";

// 관리자 상품 관리·관리 이력 (후속 4-3). 시드 상품 5개(SEED_PRODUCT)는 다른 시나리오가 쓰므로 새 상품을 등록해 바꾼다.
// 가격은 검색 시나리오의 가격 범위(15,000~30,000) 밖으로 둔다
const history = (page: Page) => page.getByRole("list", { name: "관리 이력" }).locator("> li");

test("관리자가 상품을 등록하고 바꾸면 누가·무엇을 바꿨는지 관리 이력에 최근부터 남는다", async ({
  page,
}) => {
  await login(page, "admin");
  await page.getByRole("link", { name: "상품 관리로 가기" }).click();
  await expect(page).toHaveURL(/\/products$/);

  const name = `e2e 이력 상품 ${Date.now().toString(36)}`;
  const register = page.getByRole("form", { name: "상품 등록" });
  await register.getByLabel("상품 이름").fill(name);
  // 저장이 실패해도 입력한 값은 남는다 (가격만 고쳐 다시 등록)
  await register.getByLabel("가격 (원)").fill("0");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(register.getByRole("alert")).toContainText("가격은 1원부터");
  await expect(register.getByLabel("상품 이름")).toHaveValue(name);
  await register.getByLabel("가격 (원)").fill("50,000");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(page).toHaveURL(/\/products\/\d+$/);
  await expect(history(page)).toHaveCount(1);
  await expect(history(page).first()).toContainText("등록");
  await expect(history(page).first()).toContainText("50,000원");

  const price = page.getByRole("form", { name: "가격 변경" });
  await price.getByLabel("가격 (원)").fill("60000");
  await price.getByRole("button", { name: "가격 변경" }).click();
  await expect(price.getByRole("status")).toHaveText("가격을 60,000원으로 바꿨습니다.");
  // 새로 그린 화면의 입력은 바뀐 값
  await expect(price.getByLabel("가격 (원)")).toHaveValue("60000");

  const describe = page.getByRole("form", { name: "정보 수정" });
  await describe.getByLabel("설명").fill("관리 이력 시연");
  await describe.getByRole("button", { name: "이름·설명 저장" }).click();
  await expect(describe.getByRole("status")).toBeVisible();

  await page.getByRole("button", { name: "판매 중지" }).click();
  await expect(page.getByRole("button", { name: "판매 재개" })).toBeVisible();

  // 같은 가격으로 "바꾸기"는 이력을 남기지 않는다
  await price.getByRole("button", { name: "가격 변경" }).click();
  await expect(price.getByRole("status")).toBeVisible();

  await expect(history(page)).toHaveCount(4);
  await expect(history(page).nth(0)).toContainText("판매 상태 변경");
  await expect(history(page).nth(0)).toContainText("판매 중 → 판매 중지");
  await expect(history(page).nth(1)).toContainText("정보 수정");
  await expect(history(page).nth(1)).toContainText("관리 이력 시연");
  await expect(history(page).nth(2)).toContainText("가격 변경");
  await expect(history(page).nth(2)).toContainText("50,000원 → 60,000원");
  await expect(history(page).nth(3)).toContainText("등록");
  // 바꾼 사람 = 로그인한 관리자 (토큰의 sub)
  await expect(history(page).first()).toContainText(/관리자 #\d+/);

  // 관리 목록에는 판매 중지 상품도 보인다
  await page.getByRole("link", { name: "← 상품 관리" }).click();
  const row = page
    .getByRole("list", { name: "관리 상품 목록" })
    .getByRole("listitem")
    .filter({ hasText: name });
  await expect(row).toContainText("60,000원 · 판매 중지");
});

test("관리자가 아니면 상품 관리 화면을 볼 수 없다", async ({ page }) => {
  await login(page, "user1", `/products/${SEED_PRODUCT.keyboard}`);
  await expect(page.getByRole("heading", { name: "관리자만 볼 수 있는 화면입니다" })).toBeVisible();
});
