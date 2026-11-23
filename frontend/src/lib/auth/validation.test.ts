import { describe, expect, it } from "vitest";

import { safeNextPath, validateSignUp } from "./validation";

describe("validateSignUp", () => {
  it("백엔드와 같은 아이디·비밀번호 규칙", () => {
    expect(validateSignUp("user_01", "password1")).toBeUndefined();
    expect(validateSignUp("abc", "password1")).toMatch(/아이디/);
    expect(validateSignUp("has space", "password1")).toMatch(/아이디/);
    expect(validateSignUp("user_01", "short")).toMatch(/비밀번호/);
    expect(validateSignUp("user_01", "x".repeat(65))).toMatch(/비밀번호/);
    // 72바이트 초과(BCrypt 한계): 한글 25자 = 75바이트
    expect(validateSignUp("user_01", "가".repeat(25))).toMatch(/너무 깁니다/);
  });
});

describe("safeNextPath", () => {
  it("사이트 안 경로만 돌려주고 나머지는 / 로", () => {
    expect(safeNextPath("/orders?x=1")).toBe("/orders?x=1");
    expect(safeNextPath(undefined)).toBe("/");
    expect(safeNextPath("https://evil.example")).toBe("/");
    expect(safeNextPath("//evil.example")).toBe("/");
    expect(safeNextPath("/\\evil.example")).toBe("/");
  });
});
