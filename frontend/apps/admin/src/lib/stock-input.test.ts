import { describe, expect, it } from "vitest";

import { adjustedMessage, parseStockAdjustInput, signedDelta } from "./stock-input";

function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [k, v] of Object.entries(values)) {
    data.set(k, v);
  }
  return data;
}

describe("parseStockAdjustInput", () => {
  it("입고는 양수, 차감은 음수, 쉼표·+ 허용", () => {
    expect(parseStockAdjustInput(form({ delta: "10" }))).toEqual({
      ok: true,
      value: { delta: 10, reason: null },
    });
    expect(parseStockAdjustInput(form({ delta: "+1,000", reason: " 입고 " }))).toEqual({
      ok: true,
      value: { delta: 1000, reason: "입고" },
    });
    expect(parseStockAdjustInput(form({ delta: "-3", reason: "파손" }))).toEqual({
      ok: true,
      value: { delta: -3, reason: "파손" },
    });
  });

  it("0·범위 밖·소수·빈 값·긴 사유는 거른다", () => {
    for (const delta of ["0", "", "1.5", "abc", "1000001", "-1000001", "--1", "+-1"]) {
      expect(parseStockAdjustInput(form({ delta })).ok).toBe(false);
    }
    expect(parseStockAdjustInput(form({ delta: "1", reason: "가".repeat(201) })).ok).toBe(false);
    expect(parseStockAdjustInput(form({ delta: "1000000", reason: "가".repeat(200) })).ok).toBe(
      true,
    );
  });
});

describe("문구", () => {
  it("늘림·줄임과 부호", () => {
    expect(adjustedMessage(10, 12)).toBe("재고를 10개 늘렸습니다. 지금 주문 가능 12개.");
    expect(adjustedMessage(-1500, 0)).toBe("재고를 1,500개 줄였습니다. 지금 주문 가능 0개.");
    expect(signedDelta(5)).toBe("+5");
    expect(signedDelta(-5)).toBe("-5");
  });
});
