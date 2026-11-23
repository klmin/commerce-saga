import { describe, expect, it } from "vitest";

import { isValidIdempotencyKey, orderTotal, toOrderInput } from "./order-input";

describe("toOrderInput", () => {
  it("수량이 있는 상품만 줄로, 가격은 고정 목록에서", () => {
    const result = toOrderInput({ quantities: { 1: "2", 3: "1", 5: "" }, pointAmount: "300" });
    expect(result).toMatchObject({
      input: {
        lines: [
          { productId: 1, quantity: 2, unitPrice: 39_000 },
          { productId: 3, quantity: 1, unitPrice: 18_000 },
        ],
        pointAmount: 300,
      },
    });
  });

  it("고른 상품이 없거나 수량·포인트가 이상하면 오류와 함께 입력값을 돌려준다", () => {
    expect(toOrderInput({ quantities: {}, pointAmount: "" })).toMatchObject({
      error: expect.stringMatching(/수량을 입력/),
    });
    expect(toOrderInput({ quantities: { 1: "-1" }, pointAmount: "0" })).toMatchObject({
      error: expect.stringMatching(/무선 키보드 수량/),
    });
    expect(toOrderInput({ quantities: { 1: "1001" }, pointAmount: "0" })).toMatchObject({
      error: expect.stringMatching(/0~1000/),
    });
    expect(toOrderInput({ quantities: { 1: "1.5" }, pointAmount: "0" })).toMatchObject({
      error: expect.any(String),
    });
    const tooMuch = toOrderInput({ quantities: { 2: "1" }, pointAmount: "25001" });
    expect(tooMuch).toMatchObject({
      error: expect.stringMatching(/포인트가 주문 금액보다/),
      values: { quantities: { 2: 1 }, pointAmount: 25_001 },
    });
  });

  it("포인트로 전액 결제도 된다", () => {
    expect(toOrderInput({ quantities: { 2: "1" }, pointAmount: "25000" })).toHaveProperty("input");
  });
});

describe("orderTotal / isValidIdempotencyKey", () => {
  it("합계와 키 형식", () => {
    expect(orderTotal({ 1: 1, 2: 2 })).toBe(39_000 + 50_000);
    expect(isValidIdempotencyKey("0f6b2f0e-6d0e-4a8f-9a57-3f1f1f1f1f1f")).toBe(true);
    expect(isValidIdempotencyKey("")).toBe(false);
    expect(isValidIdempotencyKey("has space")).toBe(false);
    expect(isValidIdempotencyKey("x".repeat(101))).toBe(false);
  });
});
