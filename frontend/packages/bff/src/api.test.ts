import { afterEach, describe, expect, it, vi } from "vitest";

import {
  api,
  ApiError,
  browser,
  LoginFailedError,
  loginUrl,
  startLogin,
  withoutLoginError,
  xsrfToken,
} from "./api";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("xsrfToken", () => {
  it("쿠키 XSRF-TOKEN 값을 읽는다", () => {
    expect(xsrfToken("a=1; XSRF-TOKEN=abc%3D; b=2")).toBe("abc=");
    expect(xsrfToken("a=1")).toBeUndefined();
  });
});

describe("loginUrl", () => {
  it("클라이언트 이름 없이 auto 로, 돌아올 곳은 앞 경로까지 포함한 전체 경로", () => {
    expect(loginUrl("/admin/orders?x=1")).toBe(
      "/oauth2/authorization/auto?return_to=%2Fadmin%2Forders%3Fx%3D1",
    );
  });
});

describe("api", () => {
  it("상태를 바꾸는 요청에 X-XSRF-TOKEN 을 붙이고 같은 출처 쿠키로 보낸다", async () => {
    document.cookie = "XSRF-TOKEN=tok";
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"ok":true}', { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      api("/api/cart/items/01900000-0000-7000-8000-000000000001", { method: "PUT", body: "{}" }),
    ).resolves.toEqual({
      ok: true,
    });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/cart/items/01900000-0000-7000-8000-000000000001");
    expect(init.credentials).toBe("same-origin");
    expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("tok");
    expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
  });

  it("GET 에는 CSRF 헤더를 붙이지 않는다", async () => {
    document.cookie = "XSRF-TOKEN=tok";
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(api("/api/me")).resolves.toBeUndefined();
    expect(new Headers(fetchMock.mock.calls[0][1].headers).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("오류는 Problem Details 로 읽는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ title: "Conflict", detail: "가격이 바뀌었습니다" }), {
          status: 409,
        }),
      ),
    );
    const error = await api("/api/orders", { method: "POST", body: "{}" }).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, message: "가격이 바뀌었습니다" });
  });

  it("401 이면 지금 화면으로 돌아오게 로그인을 시작한다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
    vi.spyOn(browser, "currentPath").mockReturnValue("/orders?before=x");
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    void api("/api/orders");
    await vi.waitFor(() =>
      expect(assign).toHaveBeenCalledWith(
        "/oauth2/authorization/auto?return_to=%2Forders%3Fbefore%3Dx",
      ),
    );
  });
});

describe("로그인 실패로 돌아온 화면 (?login_error)", () => {
  it("자동으로 다시 로그인하지 않고 LoginFailedError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 401 })));
    vi.spyOn(browser, "currentPath").mockReturnValue("/?login_error");
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    await expect(api("/api/me")).rejects.toBeInstanceOf(LoginFailedError);
    expect(assign).not.toHaveBeenCalled();
  });

  it("다시 로그인(force)은 login_error 를 뺀 곳으로 돌아오게 시작한다", () => {
    vi.spyOn(browser, "currentPath").mockReturnValue("/orders?login_error&before=x");
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    void startLogin(true);
    expect(assign).toHaveBeenCalledWith(
      "/oauth2/authorization/auto?return_to=%2Forders%3Fbefore%3Dx",
    );
    expect(withoutLoginError("/?login_error")).toBe("/");
  });
});
