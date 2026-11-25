import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./client";

const requestMock = vi.hoisted(() => vi.fn());
const session = vi.hoisted(() => ({ accessToken: vi.fn() }));
// proxy 가 넘기는 지금 경로와 쿠키
const incoming = vi.hoisted(() => ({
  path: "/orders?before=abc" as string | null,
  renewed: false,
}));
const redirectMock = vi.hoisted(() =>
  vi.fn((url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  }),
);

vi.mock("server-only", () => ({}));
vi.mock("./client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./client")>()),
  request: requestMock,
}));
vi.mock("@/lib/auth/server-session", () => session);
vi.mock("next/headers", () => ({
  headers: async () =>
    new Headers(incoming.path === null ? {} : { "x-cs-request-path": incoming.path }),
  cookies: async () => ({ has: (name: string) => name === "cs_renewed" && incoming.renewed }),
}));
vi.mock("next/navigation", () => ({ redirect: redirectMock }));

const { authedRequest } = await import("./authed");

beforeEach(() => {
  vi.clearAllMocks();
  session.accessToken.mockResolvedValue("at");
  incoming.path = "/orders?before=abc";
  incoming.renewed = false;
});

describe("authedRequest", () => {
  it("access token 을 Bearer 로 붙여 부른다", async () => {
    requestMock.mockResolvedValue({ ok: true });

    await expect(authedRequest("/api/points/me")).resolves.toEqual({ ok: true });

    const init = requestMock.mock.calls[0][1] as RequestInit;
    expect(requestMock.mock.calls[0][0]).toBe("/api/points/me");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer at");
  });

  it("백엔드가 401 이면 로그인 대신 renew 로 (지금 경로를 next 로)", async () => {
    requestMock.mockRejectedValue(new ApiError(401, "invalid token"));

    await expect(authedRequest("/api/orders")).rejects.toThrow(
      "NEXT_REDIRECT:/session/renew?next=%2Forders%3Fbefore%3Dabc",
    );
  });

  it("방금 renew 했는데도 401 이면 로그인으로 (renew 를 반복하지 않는다)", async () => {
    incoming.renewed = true;
    requestMock.mockRejectedValue(new ApiError(401, "invalid token"));

    await expect(authedRequest("/api/orders")).rejects.toThrow(
      "NEXT_REDIRECT:/login?next=%2Forders%3Fbefore%3Dabc",
    );
  });

  it("지금 경로를 모르거나 사이트 밖이면 next 없이", async () => {
    requestMock.mockRejectedValue(new ApiError(401, "invalid token"));

    incoming.path = null;
    await expect(authedRequest("/api/orders")).rejects.toThrow("NEXT_REDIRECT:/session/renew");

    incoming.path = "//evil.example";
    await expect(authedRequest("/api/orders")).rejects.toThrow("NEXT_REDIRECT:/session/renew");
  });

  it("access 쿠키가 없으면 백엔드를 부르지 않고 로그인으로", async () => {
    session.accessToken.mockResolvedValue(undefined);

    await expect(authedRequest("/api/orders")).rejects.toThrow(
      "NEXT_REDIRECT:/login?next=%2Forders%3Fbefore%3Dabc",
    );
    expect(requestMock).not.toHaveBeenCalled();
  });

  it("401 이 아닌 오류는 그대로 던진다 (403 은 권한 없음이라 refresh 하지 않는다)", async () => {
    requestMock.mockRejectedValue(new ApiError(403, "forbidden"));

    await expect(authedRequest("/api/admin/payment-faults")).rejects.toMatchObject({
      status: 403,
    });
    expect(redirectMock).not.toHaveBeenCalled();
  });
});
