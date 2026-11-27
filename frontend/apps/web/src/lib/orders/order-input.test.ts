import { describe, expect, it } from "vitest";

import { isValidIdempotencyKey, orderTotal, toOrderInput } from "./order-input";

// product-service 시드와 같은 상품 (화면에 보인 값). id 는 시드 고정 UUID (ADR 0018)
const KEYBOARD = "01900000-0000-7000-8000-000000000001";
const MOUSE = "01900000-0000-7000-8000-000000000002";
const HUB = "01900000-0000-7000-8000-000000000003";
const MONITOR_ARM = "01900000-0000-7000-8000-000000000005";
const PRODUCTS = [
  { id: KEYBOARD, name: "무선 키보드", price: 39_000 },
  { id: MOUSE, name: "무선 마우스", price: 25_000 },
  { id: HUB, name: "USB-C 허브", price: 18_000 },
  { id: MONITOR_ARM, name: "단종 모니터암", price: 45_000 },
];

describe("toOrderInput", () => {
  it("수량이 있는 상품만 줄로, 가격은 화면에 보인 값으로", () => {
    const result = toOrderInput(
      { quantities: { [KEYBOARD]: "2", [HUB]: "1", [MONITOR_ARM]: "" }, pointAmount: "300" },
      PRODUCTS,
    );
    expect(result).toMatchObject({
      input: {
        lines: [
          { productId: KEYBOARD, quantity: 2, unitPrice: 39_000 },
          { productId: HUB, quantity: 1, unitPrice: 18_000 },
        ],
        pointAmount: 300,
      },
    });
  });

  it("고른 상품이 없거나 수량·포인트가 이상하면 오류와 함께 입력값을 돌려준다", () => {
    expect(toOrderInput({ quantities: {}, pointAmount: "" }, PRODUCTS)).toMatchObject({
      error: expect.stringMatching(/수량을 입력/),
    });
    expect(
      toOrderInput({ quantities: { [KEYBOARD]: "-1" }, pointAmount: "0" }, PRODUCTS),
    ).toMatchObject({
      error: expect.stringMatching(/무선 키보드 수량/),
    });
    expect(
      toOrderInput({ quantities: { [KEYBOARD]: "1001" }, pointAmount: "0" }, PRODUCTS),
    ).toMatchObject({
      error: expect.stringMatching(/0~1000/),
    });
    expect(
      toOrderInput({ quantities: { [KEYBOARD]: "1.5" }, pointAmount: "0" }, PRODUCTS),
    ).toMatchObject({
      error: expect.any(String),
    });
    const tooMuch = toOrderInput({ quantities: { [MOUSE]: "1" }, pointAmount: "25001" }, PRODUCTS);
    expect(tooMuch).toMatchObject({
      error: expect.stringMatching(/포인트가 주문 금액보다/),
      values: { quantities: { [MOUSE]: 1 }, pointAmount: 25_001 },
    });
  });

  it("포인트로 전액 결제도 된다", () => {
    expect(
      toOrderInput({ quantities: { [MOUSE]: "1" }, pointAmount: "25000" }, PRODUCTS),
    ).toHaveProperty("input");
  });
});

describe("orderTotal / isValidIdempotencyKey", () => {
  it("합계와 키 형식", () => {
    expect(orderTotal({ [KEYBOARD]: 1, [MOUSE]: 2 }, PRODUCTS)).toBe(39_000 + 50_000);
    expect(isValidIdempotencyKey("0f6b2f0e-6d0e-4a8f-9a57-3f1f1f1f1f1f")).toBe(true);
    expect(isValidIdempotencyKey("")).toBe(false);
    expect(isValidIdempotencyKey("has space")).toBe(false);
    expect(isValidIdempotencyKey("x".repeat(101))).toBe(false);
  });
});
