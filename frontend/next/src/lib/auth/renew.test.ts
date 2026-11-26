import { describe, expect, it } from "vitest";

import { loginPath, renewPath, safeReturnPath } from "./renew";

describe("safeReturnPath", () => {
  it("사이트 안 경로는 그대로", () => {
    expect(safeReturnPath("/orders?before=abc")).toBe("/orders?before=abc");
  });

  it("외부 주소·스킴·프로토콜 상대·역슬래시는 / 로 (open redirect 방지)", () => {
    for (const next of [
      "https://evil.example",
      "//evil.example",
      "/\\evil.example",
      "/\t/evil.example",
      "javascript:alert(1)",
      "orders",
      "",
      null,
      undefined,
    ]) {
      expect(safeReturnPath(next)).toBe("/");
    }
  });

  it("renew 자신으로는 돌아가지 않는다", () => {
    expect(safeReturnPath("/session/renew")).toBe("/");
    expect(safeReturnPath("/session/renew?next=%2Forders")).toBe("/");
  });
});

describe("loginPath / renewPath", () => {
  it("돌아갈 경로를 next 로 붙인다 (/ 이면 생략)", () => {
    expect(loginPath("/orders?before=abc")).toBe("/login?next=%2Forders%3Fbefore%3Dabc");
    expect(loginPath("/")).toBe("/login");
    expect(renewPath("/cart")).toBe("/session/renew?next=%2Fcart");
    expect(renewPath("//evil.example")).toBe("/session/renew");
  });
});
