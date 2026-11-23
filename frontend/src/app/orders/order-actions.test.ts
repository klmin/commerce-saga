import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";

const placeOrderMock = vi.hoisted(() => vi.fn());
const redirectMock = vi.hoisted(() =>
  vi.fn((url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  }),
);

vi.mock("server-only", () => ({}));
vi.mock("@/lib/api/orders", () => ({ placeOrder: placeOrderMock }));
vi.mock("next/navigation", () => ({
  redirect: redirectMock,
  // 테스트에서는 redirect 가 던진 오류만 다시 던진다
  unstable_rethrow: (e: unknown) => {
    if (e instanceof Error && e.message.startsWith("NEXT_REDIRECT")) {
      throw e;
    }
  },
}));

const { placeOrderAction } = await import("./order-actions");

const KEY = "11111111-2222-4333-8444-555555555555";

function form(fields: Record<string, string>) {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) {
    data.set(k, v);
  }
  return data;
}

beforeEach(() => {
  placeOrderMock.mockReset();
  redirectMock.mockClear();
});

describe("placeOrderAction", () => {
  it("폼의 Idempotency-Key 와 고정 가격으로 주문하고 상세로 간다", async () => {
    placeOrderMock.mockResolvedValue({ orderId: "01a2-order" });

    await expect(
      placeOrderAction(
        { idempotencyKey: KEY },
        form({ idempotencyKey: KEY, "qty-2": "2", pointAmount: "100" }),
      ),
    ).rejects.toThrow("NEXT_REDIRECT:/orders/01a2-order");

    expect(placeOrderMock).toHaveBeenCalledWith(
      { lines: [{ productId: 2, quantity: 2, unitPrice: 25_000 }], pointAmount: 100 },
      KEY,
    );
  });

  it("응답을 못 받으면 같은 키를 돌려줘 다시 눌러도 같은 주문이 된다", async () => {
    placeOrderMock.mockRejectedValue(new TypeError("fetch failed"));

    const state = await placeOrderAction(
      { idempotencyKey: KEY },
      form({ idempotencyKey: KEY, "qty-1": "1" }),
    );

    expect(state).toMatchObject({ idempotencyKey: KEY, error: expect.stringMatching(/두 번/) });
  });

  it("같은 키에 다른 내용(422)이면 새 키로 바꾼다", async () => {
    placeOrderMock.mockRejectedValue(new ApiError(422, "Idempotency-Key reused"));

    const state = await placeOrderAction(
      { idempotencyKey: KEY },
      form({ idempotencyKey: KEY, "qty-1": "1" }),
    );

    expect(state.idempotencyKey).not.toBe(KEY);
    expect(state.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(state.error).toMatch(/내용이 달라/);
  });

  it("백엔드 검증 오류는 메시지를 보여 주고 키는 그대로", async () => {
    placeOrderMock.mockRejectedValue(new ApiError(400, "pointAmount must not exceed total"));

    const state = await placeOrderAction(
      { idempotencyKey: KEY },
      form({ idempotencyKey: KEY, "qty-1": "1" }),
    );

    expect(state).toEqual({ idempotencyKey: KEY, error: "pointAmount must not exceed total" });
  });

  it("입력이 잘못되면 백엔드를 부르지 않는다. 키가 이상하면 새로 만든다", async () => {
    const state = await placeOrderAction(
      { idempotencyKey: KEY },
      form({ idempotencyKey: "has space", pointAmount: "1" }),
    );

    expect(placeOrderMock).not.toHaveBeenCalled();
    expect(state.error).toMatch(/수량을 입력/);
    expect(state.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("로그인이 풀려 로그인 화면으로 보내는 redirect 는 삼키지 않는다", async () => {
    placeOrderMock.mockRejectedValue(new Error("NEXT_REDIRECT:/login"));

    await expect(
      placeOrderAction({ idempotencyKey: KEY }, form({ idempotencyKey: KEY, "qty-1": "1" })),
    ).rejects.toThrow("NEXT_REDIRECT:/login");
  });
});
