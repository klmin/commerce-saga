import { describe, expect, it } from "vitest";

import { serverEnv } from "./env";

describe("serverEnv", () => {
  it("값이 없으면 기본값(Gateway :8000, Secure 쿠키)을 쓴다", () => {
    expect(serverEnv({})).toEqual({ BACKEND_URL: "http://localhost:8000", COOKIE_SECURE: true });
  });

  it("COOKIE_SECURE=false 로 끌 수 있다", () => {
    expect(serverEnv({ COOKIE_SECURE: "false" }).COOKIE_SECURE).toBe(false);
  });

  it("URL 형식이 아니면 변수 이름을 담아 실패한다", () => {
    expect(() => serverEnv({ BACKEND_URL: "backend:8000" })).toThrow(/BACKEND_URL/);
    expect(() => serverEnv({ COOKIE_SECURE: "yes" })).toThrow(/COOKIE_SECURE/);
  });
});
