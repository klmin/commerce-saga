import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";

import { login, logout, refresh, signUp } from "./backend";

vi.mock("next/server", () => ({ connection: async () => {} }));

function respond(status: number, body?: unknown, setCookie?: string) {
  const headers = new Headers(body === undefined ? {} : { "Content-Type": "application/json" });
  if (setCookie) {
    headers.append("Set-Cookie", setCookie);
  }
  const fetchMock = vi
    .fn()
    .mockResolvedValue(
      new Response(body === undefined ? null : JSON.stringify(body), { status, headers }),
    );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

beforeEach(() => {
  vi.stubEnv("BACKEND_URL", "http://gateway:8000");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

const tokens = {
  accessToken: "at",
  tokenType: "Bearer",
  expiresIn: 900,
  expiresAt: "2026-11-23T00:15:00Z",
};
const rotated =
  "refresh_token=fam.secret; Path=/auth; Max-Age=1209600; Secure; HttpOnly; SameSite=Lax";

describe("auth backend", () => {
  it("로그인: 본문의 access token 과 Set-Cookie 의 refresh token 을 세션으로", async () => {
    const fetchMock = respond(200, tokens, rotated);

    await expect(
      login("user1", "pw", { userAgent: "Mozilla/5.0", ip: "203.0.113.7" }),
    ).resolves.toEqual({
      accessToken: "at",
      accessMaxAge: 900,
      refreshToken: "fam.secret",
      refreshMaxAge: 1209600,
    });
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(String(url)).toBe("http://gateway:8000/auth/login");
    expect(new Headers(init.headers).get("User-Agent")).toBe("Mozilla/5.0");
    // Gateway 가 IP rate limit 을 브라우저마다 걸게 브라우저 IP 를 넘긴다
    expect(new Headers(init.headers).get("X-Forwarded-For")).toBe("203.0.113.7");
  });

  it("refresh: refresh token 을 Cookie 로, CSRF 헤더를 붙여 보낸다. 회전 안 한 응답은 refresh token 없음", async () => {
    const fetchMock = respond(200, tokens);

    await expect(refresh("fam.old", {})).resolves.toEqual({
      accessToken: "at",
      accessMaxAge: 900,
      refreshToken: undefined,
      refreshMaxAge: undefined,
    });
    const headers = new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers);
    expect(headers.get("Cookie")).toBe("refresh_token=fam.old");
    expect(headers.get("X-Requested-With")).toBeTruthy();
    // IP 를 모르면 X-Forwarded-For 를 보내지 않는다 (Gateway 는 연결 주소로)
    expect(headers.get("X-Forwarded-For")).toBeNull();
  });

  it("거절은 Problem Details 메시지를 담은 ApiError", async () => {
    respond(401, { status: 401, detail: "아이디 또는 비밀번호가 올바르지 않습니다." });

    const error = await login("user1", "bad", {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect((error as ApiError).message).toContain("올바르지 않습니다");
  });

  it("로그인 응답에 refresh token 이 없으면 실패로 본다", async () => {
    respond(200, tokens);

    await expect(login("user1", "pw", {})).rejects.toBeInstanceOf(ApiError);
  });

  it("가입은 userId 를, 로그아웃은 Cookie·CSRF 헤더로", async () => {
    respond(201, { userId: 1000 });
    await expect(signUp("new_user", "password1", {})).resolves.toBe(1000);

    const fetchMock = respond(204);
    await logout("fam.secret", {});
    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get("Cookie")).toBe(
      "refresh_token=fam.secret",
    );
  });
});
