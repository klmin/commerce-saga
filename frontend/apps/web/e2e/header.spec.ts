import { expect, type Page, test } from "@playwright/test";

import { signUpFresh } from "./helpers";

// 사용자 화면 틀 (후속 9-5a, ADR 0021): 헤더(검색·장바구니 개수·내 주문·내 정보 메뉴)와 카테고리 메뉴
const products = (page: Page) =>
  page.getByRole("list", { name: "상품 목록" }).getByRole("listitem");

test("카테고리 메뉴를 누르면 그 카테고리 상품만 보이고, 검색어는 카테고리 안에서 찾는다", async ({
  page,
}) => {
  await signUpFresh(page);
  const categories = page.getByRole("navigation", { name: "카테고리" });
  await expect(categories.getByRole("link", { name: "전체" })).toHaveAttribute(
    "aria-current",
    "page",
  );

  await categories.getByRole("link", { name: "디지털" }).click();
  await expect(page).toHaveURL(/\?category=digital$/);
  await expect(categories.getByRole("link", { name: "디지털" })).toHaveAttribute(
    "aria-current",
    "page",
  );
  await expect(page.getByRole("heading", { name: "디지털" })).toBeVisible();
  // 색인은 CDC 로 들어와 처음 몇 초는 비어 있을 수 있다
  await expect(async () => {
    await page.reload();
    await expect(products(page).filter({ hasText: "무선 마우스" })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 30_000 });

  // 헤더 검색은 고른 카테고리를 유지한다
  const search = page.getByRole("search", { name: "상품 검색" });
  await search.getByLabel("상품 검색어").fill("마우");
  await search.getByRole("button", { name: "검색" }).click();
  await expect(page).toHaveURL(/\?q=%EB%A7%88%EC%9A%B0&category=digital$/);
  await expect(page.getByRole("heading", { name: '디지털 · "마우" 검색' })).toBeVisible();
  await expect(products(page).filter({ hasText: "무선 마우스" })).toBeVisible();

  // 다른 카테고리로 옮기면 검색어는 남고 마우스(디지털)는 빠진다
  await categories.getByRole("link", { name: "주방" }).click();
  await expect(page).toHaveURL(/\?q=%EB%A7%88%EC%9A%B0&category=kitchen$/);
  await expect(page.getByText("조건에 맞는 상품이 없습니다.")).toBeVisible();
  await expect(products(page)).toHaveCount(0);

  await categories.getByRole("link", { name: "전체" }).click();
  await expect(page).toHaveURL(/\?q=%EB%A7%88%EC%9A%B0$/);
});

test("헤더의 장바구니 개수는 담으면 바로 바뀌고, 내 정보 메뉴로 내역 화면에 간다", async ({
  page,
}) => {
  await signUpFresh(page);
  const header = page.getByRole("banner");
  await expect(header.getByRole("link", { name: "장바구니", exact: true })).toBeVisible();

  await products(page)
    .filter({ hasText: "무선 키보드" })
    .getByRole("button", { name: "무선 키보드 장바구니 담기" })
    .click();
  await expect(page).toHaveURL(/\/cart$/);
  await expect(header.getByRole("link", { name: "장바구니 (1개)" })).toBeVisible();

  await header.getByRole("button", { name: "내 정보" }).click();
  await page.getByRole("menuitem", { name: "포인트" }).click();
  await expect(page).toHaveURL(/\/points$/);
  // 메뉴는 이동하면 닫힌다
  await expect(page.getByRole("menu")).toHaveCount(0);
});
