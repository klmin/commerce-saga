import { expect, type Page, test } from "@playwright/test";

// 개발용 시드 사용자 (auth-service db/dev-seed, README). 로컬 개발용 값이다
const DEV_PASSWORD = process.env.E2E_DEV_PASSWORD ?? "local-dev-pass";

// 주문 Saga 전체(order·inventory·point·payment + Kafka)가 떠 있어야 한다. 가입한 새 사용자로 돌아 시드 잔액에 기대지 않는다
async function signUpFresh(page: Page) {
  const loginId = `e2e_${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`;
  await page.goto("/signup");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호", { exact: true }).fill("e2e-password");
  await page.getByLabel("비밀번호 확인").fill("e2e-password");
  await page.getByRole("button", { name: "가입하기" }).click();
  // 가입하면 바로 로그인되어 홈으로 간다
  await expect(page).toHaveURL(/\/$/);
  // 가입 축하 포인트 계정이 생길 때까지 (시나리오 13). 가입 화면 안내 문구와 헷갈리지 않게 잔액 문단만 본다
  await expect(async () => {
    await page.reload();
    await expect(page.getByText("1,000P", { exact: true })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 20_000 });
}

test("포인트를 써서 주문하면 Saga 가 재고 → 포인트 → 결제 → 완료로 진행된다", async ({ page }) => {
  await signUpFresh(page);
  await page.goto("/orders/new?productId=2");
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
  await page.goto("/orders/new?productId=5");
  await page.getByRole("button", { name: "주문하기" }).click();

  await expect(page.getByTestId("saga-state")).toHaveText("취소", { timeout: 20_000 });
  await expect(page.getByText("취소 사유: insufficient stock")).toBeVisible();
  await expect(page.getByText("STOCK_RESERVATION_FAILED")).toBeVisible();
  await expect(page.getByText("STOCK_RELEASED")).toHaveCount(0);
});

test("포인트가 모자라면 포인트 단계에서 실패하고 재고를 되돌린다 (보상)", async ({ page }) => {
  await signUpFresh(page);
  await page.goto("/orders/new?productId=1");
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
  await page.goto("/orders/new?productId=3");
  const submit = page.getByRole("button", { name: "주문하기" });
  // 응답 전에 연달아 제출한다 (버튼이 잠기기 전에 같은 폼을 세 번 보낸다)
  await page
    .locator("form")
    .last()
    .evaluate((form: HTMLFormElement) => {
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
  await page.goto("/orders/new?productId=1");
  await page.getByRole("button", { name: "주문하기" }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
  const mine = page.url();

  const other = await browser.newPage();
  await other.goto("/login?next=%2Forders");
  await other.getByLabel("아이디").fill("user2");
  await other.getByLabel("비밀번호").fill(DEV_PASSWORD);
  await other.getByRole("button", { name: "로그인" }).click();
  await expect(other).toHaveURL(/\/orders$/);

  await other.goto(mine);
  await expect(other.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
  await other.goto("/orders/01a20000-0000-7000-8000-000000000000");
  await expect(other.getByRole("heading", { name: "페이지를 찾을 수 없습니다" })).toBeVisible();
  await other.close();
});
