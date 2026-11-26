import { describe, expect, it } from "vitest";

import { numberCursor, uuidCursor } from "./cursor";

describe("cursor", () => {
  it("UUID 커서만 받는다", () => {
    const id = "0196f3a2-1b2c-7d3e-8f40-123456789abc";
    expect(uuidCursor(id)).toBe(id);
    expect(uuidCursor("not-a-uuid")).toBeUndefined();
    expect(uuidCursor([id, id])).toBeUndefined();
    expect(uuidCursor(undefined)).toBeUndefined();
  });

  it("양의 정수 커서만 받는다", () => {
    expect(numberCursor("42")).toBe(42);
    expect(numberCursor("0")).toBeUndefined();
    expect(numberCursor("-1")).toBeUndefined();
    expect(numberCursor("1e3")).toBeUndefined();
    expect(numberCursor("99999999999999999")).toBeUndefined(); // 안전한 정수 범위 밖
    expect(numberCursor(undefined)).toBeUndefined();
  });
});
