import { expect, type Page, test } from "@playwright/test";

// 상품 검색 (후속 4-2c, ADR 0010). product-cdc 커넥터 → search-service → Elasticsearch 가 떠 있어야 한다.
// 개발용 시드 사용자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("아이디").fill("user1");
  await page.getByLabel("비밀번호").fill(DEV_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/$/);
}

const products = (page: Page) =>
  page.getByRole("list", { name: "상품 목록" }).getByRole("listitem");

test("검색어로 상품을 찾고, 조건은 URL 에 남으며, 전체 보기로 돌아간다", async ({ page }) => {
  await login(page);
  await expect(products(page).filter({ hasText: "무선 키보드" })).toBeVisible();

  const search = page.getByRole("search");
  await search.getByLabel("검색어").fill("마우");
  await search.getByRole("button", { name: "검색" }).click();
  await expect(page).toHaveURL(/[?&]q=%EB%A7%88%EC%9A%B0/);

  // 색인은 CDC 로 들어와 처음 몇 초는 비어 있을 수 있다
  await expect(async () => {
    await page.reload();
    await expect(products(page).filter({ hasText: "무선 마우스" })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 30_000 });
  await expect(products(page).filter({ hasText: "무선 키보드" })).toHaveCount(0);
  await expect(page.getByText(/검색 결과 1개/)).toBeVisible();

  // 검색 결과에서 바로 주문 화면으로 (가격은 주문 때 다시 확인)
  await expect(
    products(page).filter({ hasText: "무선 마우스" }).getByRole("link", { name: "주문" }),
  ).toHaveAttribute("href", "/orders/new?productId=2");

  await page.getByRole("link", { name: "전체 보기" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(products(page).filter({ hasText: "무선 키보드" })).toBeVisible();
});

test("가격 범위와 정렬", async ({ page }) => {
  await login(page);
  await page.goto("/?minPrice=15000&maxPrice=30000&sort=price-asc");

  await expect(async () => {
    await page.reload();
    await expect(page.getByText(/검색 결과 2개/)).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 30_000 });
  // 시드: USB-C 허브 18,000 < 무선 마우스 25,000 (다른 시나리오가 가격을 바꿨다 돌려놓는다)
  const names = await products(page).locator("span.font-medium").allTextContents();
  expect(names).toEqual(["USB-C 허브", "무선 마우스"]);
  await expect(page.getByLabel("정렬")).toHaveValue("price-asc");
});
