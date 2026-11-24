import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";

const backend = vi.hoisted(() => ({ revokeSession: vi.fn(), logoutAll: vi.fn() }));
const session = vi.hoisted(() => ({ accessToken: vi.fn(), clearSession: vi.fn() }));
const redirectMock = vi.hoisted(() =>
  vi.fn((url: string) => {
    throw new Error(`NEXT_REDIRECT:${url}`);
  }),
);

vi.mock("server-only", () => ({}));
vi.mock("@/lib/auth/backend", () => backend);
vi.mock("@/lib/auth/server-session", () => session);
vi.mock("@/lib/log", () => ({ logError: vi.fn() }));
vi.mock("next/headers", () => ({ headers: async () => new Headers() }));
vi.mock("next/navigation", () => ({ redirect: redirectMock }));

const { logoutAllAction, revokeSessionAction } = await import("./session-actions");

const SESSION = "0196f3a2-1b2c-4d3e-8f40-123456789abc";

function form(sessionId: string) {
  const data = new FormData();
  data.set("sessionId", sessionId);
  return data;
}

beforeEach(() => {
  vi.clearAllMocks();
  session.accessToken.mockResolvedValue("at");
});

describe("revokeSessionAction", () => {
  it("그 기기를 로그아웃하고 목록으로", async () => {
    await expect(revokeSessionAction(form(SESSION))).rejects.toThrow("NEXT_REDIRECT:/sessions");
    expect(backend.revokeSession).toHaveBeenCalledWith("at", SESSION, expect.anything());
  });

  it("세션 id 형식이 아니면 백엔드를 부르지 않는다", async () => {
    await expect(revokeSessionAction(form("../logout-all"))).rejects.toThrow(
      "NEXT_REDIRECT:/sessions?error=invalid",
    );
    expect(backend.revokeSession).not.toHaveBeenCalled();
  });

  it("이미 없는 세션(404)은 목록만, 그 밖의 실패는 오류 표시, 401 은 로그인으로", async () => {
    backend.revokeSession.mockRejectedValueOnce(new ApiError(404, "not found"));
    await expect(revokeSessionAction(form(SESSION))).rejects.toThrow("NEXT_REDIRECT:/sessions");

    backend.revokeSession.mockRejectedValueOnce(new ApiError(500, "boom"));
    await expect(revokeSessionAction(form(SESSION))).rejects.toThrow(
      "NEXT_REDIRECT:/sessions?error=failed",
    );

    backend.revokeSession.mockRejectedValueOnce(new ApiError(401, "expired"));
    await expect(revokeSessionAction(form(SESSION))).rejects.toThrow(
      "NEXT_REDIRECT:/login?next=%2Fsessions",
    );
  });

  it("로그인하지 않았으면 로그인으로", async () => {
    session.accessToken.mockResolvedValue(undefined);
    await expect(revokeSessionAction(form(SESSION))).rejects.toThrow(
      "NEXT_REDIRECT:/login?next=%2Fsessions",
    );
  });
});

describe("logoutAllAction", () => {
  it("모든 기기 로그아웃 후 이 브라우저 쿠키도 지우고 로그인으로", async () => {
    await expect(logoutAllAction()).rejects.toThrow("NEXT_REDIRECT:/login");
    expect(backend.logoutAll).toHaveBeenCalledWith("at", expect.anything());
    expect(session.clearSession).toHaveBeenCalled();
  });

  it("백엔드 실패면 쿠키를 그대로 두고 오류 표시", async () => {
    backend.logoutAll.mockRejectedValueOnce(new Error("unreachable"));
    await expect(logoutAllAction()).rejects.toThrow("NEXT_REDIRECT:/sessions?error=failed");
    expect(session.clearSession).not.toHaveBeenCalled();
  });
});
