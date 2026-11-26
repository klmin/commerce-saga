import { beforeEach, describe, expect, it, vi } from "vitest";

const store = vi.hoisted(() => ({ set: vi.fn(), delete: vi.fn() }));

vi.mock("server-only", () => ({}));
vi.mock("next/headers", () => ({ cookies: async () => store }));

const { saveSession } = await import("./server-session");

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubEnv("COOKIE_SECURE", "true");
});

describe("saveSession", () => {
  it("세션 쿠키를 심고 '방금 renew 했음' 표식을 지운다", async () => {
    await saveSession({
      accessToken: "at",
      accessMaxAge: 900,
      refreshToken: "fam.1",
      refreshMaxAge: 60,
    });

    expect(store.set).toHaveBeenCalledWith("cs_access", "at", expect.anything());
    expect(store.set).toHaveBeenCalledWith("cs_refresh", "fam.1", expect.anything());
    expect(store.delete).toHaveBeenCalledWith("cs_renewed");
  });
});
