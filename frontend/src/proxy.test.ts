import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";
import { resetRefreshOnce } from "@/lib/auth/refresh-once";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/auth/session";

import { proxy } from "./proxy";

const refreshMock = vi.hoisted(() => vi.fn());
vi.mock("@/lib/auth/backend", () => ({ refresh: refreshMock }));

function req(path: string, cookies: Record<string, string> = {}) {
  const cookie = Object.entries(cookies)
    .map(([k, v]) => `${k}=${v}`)
    .join("; ");
  return new NextRequest(new URL(path, "http://localhost:3000"), {
    // 앞 프록시가 붙인 값이 맨 오른쪽. 그 앞은 브라우저가 마음대로 보낼 수 있다
    headers: { "x-forwarded-for": "203.0.113.1, 198.51.100.9", ...(cookie ? { cookie } : {}) },
  });
}

beforeEach(() => {
  vi.stubEnv("COOKIE_SECURE", "true");
});

afterEach(() => {
  vi.unstubAllEnvs();
  refreshMock.mockReset();
  resetRefreshOnce();
});

describe("proxy", () => {
  it("access 쿠키가 있으면 그대로 통과", async () => {
    const res = await proxy(req("/", { [ACCESS_COOKIE]: "at" }));

    expect(res.headers.get("location")).toBeNull();
    expect(refreshMock).not.toHaveBeenCalled();
  });

  it("쿠키가 없으면 보호된 화면은 로그인으로 (돌아올 경로를 붙여서), 로그인 화면은 통과", async () => {
    const res = await proxy(req("/orders?page=2"));
    expect(new URL(res.headers.get("location")!).pathname).toBe("/login");
    expect(new URL(res.headers.get("location")!).searchParams.get("next")).toBe("/orders?page=2");

    expect((await proxy(req("/login"))).headers.get("location")).toBeNull();
    expect((await proxy(req("/signup"))).headers.get("location")).toBeNull();
  });

  it("access 가 만료되고 refresh 가 있으면 새 토큰을 쿠키로 심고 이번 요청에도 새 access 를 넘긴다", async () => {
    refreshMock.mockResolvedValue({
      accessToken: "new-at",
      accessMaxAge: 900,
      refreshToken: "fam.new",
      refreshMaxAge: 1209600,
    });

    const res = await proxy(req("/", { [REFRESH_COOKIE]: "fam.old" }));

    expect(refreshMock).toHaveBeenCalledWith("fam.old", {
      ip: "198.51.100.9",
      userAgent: undefined,
    });
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("new-at");
    expect(res.cookies.get(REFRESH_COOKIE)?.value).toBe("fam.new");
    expect(res.cookies.get(ACCESS_COOKIE)).toMatchObject({
      httpOnly: true,
      secure: true,
      sameSite: "lax",
    });
    // 서버 컴포넌트로 넘어가는 요청 쿠키도 바뀐다
    expect(res.headers.get("x-middleware-request-cookie")).toContain(`${ACCESS_COOKIE}=new-at`);
  });

  it("refresh 가 거절되면(만료·재사용) 쿠키를 지우고 로그인으로", async () => {
    refreshMock.mockRejectedValue(new ApiError(401, "invalid"));

    const res = await proxy(req("/", { [REFRESH_COOKIE]: "fam.stolen" }));

    expect(new URL(res.headers.get("location")!).pathname).toBe("/login");
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("");
    expect(res.cookies.get(REFRESH_COOKIE)?.value).toBe("");
  });

  it("통과할 때 지금 경로를 요청 헤더로 넘긴다 (브라우저가 보낸 같은 이름 헤더·_rsc 는 버린다)", async () => {
    const request = req("/orders?before=abc&_rsc=xyz", { [ACCESS_COOKIE]: "at" });
    request.headers.set("x-cs-request-path", "//evil.example");

    const res = await proxy(request);

    expect(res.headers.get("x-middleware-request-x-cs-request-path")).toBe("/orders?before=abc");
  });

  it("renew 경로는 쿠키를 건드리지 않고 그대로 (refresh 도 하지 않는다)", async () => {
    const res = await proxy(req("/session/renew?next=%2Forders", { [REFRESH_COOKIE]: "fam.old" }));

    expect(res.headers.get("location")).toBeNull();
    expect(refreshMock).not.toHaveBeenCalled();
    expect(res.cookies.getAll()).toEqual([]);
  });

  it("백엔드에 닿지 않으면 쿠키는 그대로 두고 통과 (다음 요청에서 다시 시도)", async () => {
    refreshMock.mockRejectedValue(new TypeError("fetch failed"));
    vi.spyOn(console, "error").mockImplementation(() => {});

    const res = await proxy(req("/", { [REFRESH_COOKIE]: "fam.old" }));

    expect(res.headers.get("location")).toBeNull();
    expect(res.cookies.get(REFRESH_COOKIE)).toBeUndefined();
  });
});
