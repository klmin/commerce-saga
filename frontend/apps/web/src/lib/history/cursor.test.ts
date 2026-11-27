import { describe, expect, it } from "vitest";

import { uuidCursor } from "./cursor";

describe("cursor", () => {
  it("UUID 커서만 받는다", () => {
    const id = "0196f3a2-1b2c-7d3e-8f40-123456789abc";
    expect(uuidCursor(id)).toBe(id);
    expect(uuidCursor("not-a-uuid")).toBeUndefined();
    expect(uuidCursor("42")).toBeUndefined(); // 예전 숫자 알림 커서
    expect(uuidCursor([id, id])).toBeUndefined();
    expect(uuidCursor(undefined)).toBeUndefined();
  });
});
