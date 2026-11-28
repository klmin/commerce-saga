import { describe, expect, it } from "vitest";

import { stockLabel, stockState } from "./stock";

describe("stockState", () => {
  it("확인 중·확인 불가·품절·적음·충분", () => {
    expect(stockLabel(stockState(3, true))).toBe("재고 확인 중");
    expect(stockLabel(stockState(undefined))).toBe("재고 확인 불가");
    expect(stockLabel(stockState(0))).toBe("품절");
    expect(stockLabel(stockState(-1))).toBe("품절");
    expect(stockLabel(stockState(5))).toBe("5개 남음");
    expect(stockLabel(stockState(6))).toBe("재고 6개");
  });
});
