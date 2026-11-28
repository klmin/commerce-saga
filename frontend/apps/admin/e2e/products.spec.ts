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
  await register.getByLabel("카테고리").selectOption({ label: "디지털" });
  // 저장이 실패해도 입력한 값은 남는다 (가격만 고쳐 다시 등록)
  await register.getByLabel("가격 (원)").fill("0");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(register.getByRole("alert")).toContainText("가격은 1원부터");
  await expect(register.getByLabel("상품 이름")).toHaveValue(name);
  await register.getByLabel("가격 (원)").fill("50,000");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/);
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
  await describe.getByLabel("카테고리").selectOption({ label: "주방" });
  await describe.getByRole("button", { name: "정보 저장" }).click();
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
  await expect(history(page).nth(1)).toContainText("디지털 → 주방");
  await expect(history(page).nth(2)).toContainText("가격 변경");
  await expect(history(page).nth(2)).toContainText("50,000원 → 60,000원");
  await expect(history(page).nth(3)).toContainText("등록");
  // 바꾼 사람 = 로그인한 관리자 (토큰의 sub, UUID 끝 8자로 보인다)
  await expect(history(page).first()).toContainText(/관리자 #[0-9a-f]{8}/);

  // 관리 목록에는 판매 중지 상품도 보인다
  await page.getByRole("link", { name: "← 상품 관리" }).click();
  const row = page
    .getByRole("list", { name: "관리 상품 목록" })
    .getByRole("listitem")
    .filter({ hasText: name });
  await expect(row).toContainText("60,000원 · 판매 중지");
});

// 재고 조정 (ADR 0019): 새로 등록한 상품은 재고가 없고(입고 전), 입고해야 주문할 수 있다
test("관리자가 새 상품에 입고하고 차감하면 재고와 조정 이력이 바뀌고, 가용보다 많이 뺄 수는 없다", async ({
  page,
}) => {
  await login(page, "admin");
  await page.getByRole("link", { name: "상품 관리로 가기" }).click();

  const register = page.getByRole("form", { name: "상품 등록" });
  await register.getByLabel("상품 이름").fill(`e2e 재고 상품 ${Date.now().toString(36)}`);
  await register.getByLabel("카테고리").selectOption({ label: "기타" });
  await register.getByLabel("가격 (원)").fill("50000");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/);

  const current = page.getByLabel("현재 재고");
  const adjust = page.getByRole("form", { name: "재고 조정" });
  const history = page.getByRole("list", { name: "재고 조정 이력" }).locator("> li");
  await expect(current).toContainText("입고 전");

  await adjust.getByLabel("조정 수량").fill("10");
  await adjust.getByLabel("사유 (선택)").fill("e2e 첫 입고");
  await adjust.getByRole("button", { name: "재고 조정" }).click();
  await expect(adjust.getByRole("status")).toHaveText(
    "재고를 10개 늘렸습니다. 지금 주문 가능 10개.",
  );
  await expect(current).toContainText("주문 가능 10개");
  // 성공하면 입력을 비운다 (같은 값을 다시 눌러 두 번 입고하지 않게)
  await expect(adjust.getByLabel("조정 수량")).toHaveValue("");
  await expect(history).toHaveCount(1);
  await expect(history.first()).toContainText("+10개");
  await expect(history.first()).toContainText("e2e 첫 입고");
  await expect(history.first()).toContainText(/관리자 #[0-9a-f]{8}/);

  // 가용보다 많이 빼면 409, 재고·이력은 그대로
  await adjust.getByLabel("조정 수량").fill("-11");
  await adjust.getByRole("button", { name: "재고 조정" }).click();
  await expect(adjust.getByRole("alert")).toContainText(
    "가용 재고(10개)보다 많이 차감할 수 없습니다.",
  );
  await expect(adjust.getByLabel("조정 수량")).toHaveValue("-11");
  await expect(current).toContainText("주문 가능 10개");

  await adjust.getByLabel("조정 수량").fill("-4");
  await adjust.getByRole("button", { name: "재고 조정" }).click();
  await expect(adjust.getByRole("status")).toHaveText("재고를 4개 줄였습니다. 지금 주문 가능 6개.");
  await expect(history).toHaveCount(2);
  await expect(history.first()).toContainText("-4개");
  await expect(history.first()).toContainText("→ 6개");
});

test("관리자가 아니면 상품 관리 화면을 볼 수 없다", async ({ page }) => {
  await login(page, "user1", `/products/${SEED_PRODUCT.keyboard}`);
  await expect(page.getByRole("heading", { name: "관리자만 볼 수 있는 화면입니다" })).toBeVisible();
});

// 대표 이미지 (ADR 0021): 올리기 전에 미리 보고, 올리면 관리 화면·목록 썸네일에 보이고 관리 이력에 남는다
// 1x1 PNG (서비스는 내용의 앞 바이트로 형식을 본다)
const PNG_1X1 = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  "base64",
);

test("관리자가 대표 이미지를 미리 보고 올리면 썸네일로 보이고, 형식이 아닌 파일은 올리지 않는다", async ({
  page,
}) => {
  await login(page, "admin");
  await page.getByRole("link", { name: "상품 관리로 가기" }).click();

  const name = `e2e 이미지 상품 ${Date.now().toString(36)}`;
  const register = page.getByRole("form", { name: "상품 등록" });
  await register.getByLabel("상품 이름").fill(name);
  await register.getByLabel("카테고리").selectOption({ label: "생활" });
  await register.getByLabel("가격 (원)").fill("50000");
  await register.getByRole("button", { name: "등록" }).click();
  await expect(page).toHaveURL(/\/products\/[0-9a-f-]{36}$/);

  const image = page.getByRole("form", { name: "대표 이미지" });
  await expect(image.getByLabel("이미지 없음")).toBeVisible();

  // 형식이 아니면 화면에서 막는다 (요청하지 않음)
  await image.getByLabel("이미지 파일").setInputFiles({
    name: "a.gif",
    mimeType: "image/gif",
    buffer: Buffer.from("GIF89a"),
  });
  await image.getByRole("button", { name: "이미지 올리기" }).click();
  await expect(image.getByRole("alert")).toHaveText("jpg·png·webp 이미지만 올릴 수 있습니다.");

  await image.getByLabel("이미지 파일").setInputFiles({
    name: "dot.png",
    mimeType: "image/png",
    buffer: PNG_1X1,
  });
  await expect(image.getByAltText("올릴 이미지 미리보기")).toBeVisible();
  await image.getByRole("button", { name: "이미지 올리기" }).click();
  await expect(image.getByRole("status")).toHaveText("이미지를 바꿨습니다.");

  // 새 이미지는 Gateway /api 로 product-service 가 읽어 준다 (실제로 그려졌는지 = 너비가 있다)
  const current = image.getByAltText(`${name} 이미지`);
  await expect(current).toHaveAttribute(
    "src",
    /^\/api\/products\/[0-9a-f-]{36}\/images\/[0-9a-z-]+\.png$/,
  );
  await expect.poll(() => current.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(1);
  await expect(image.getByAltText("올릴 이미지 미리보기")).toHaveCount(0);
  await expect(history(page).first()).toContainText("이미지 변경");
  await expect(history(page).first()).toContainText(/\(없음\) → [0-9a-z-]+\.png/);

  await page.getByRole("link", { name: "← 상품 관리" }).click();
  const row = page
    .getByRole("list", { name: "관리 상품 목록" })
    .getByRole("listitem")
    .filter({ hasText: name });
  await expect(row).toContainText("생활 · 50,000원");
  await expect(row.getByAltText(`${name} 이미지`)).toBeVisible();
});
