import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";

const setPaymentFaultsMock = vi.hoisted(() => vi.fn());

vi.mock("server-only", () => ({}));
vi.mock("@/lib/api/admin", () => ({ setPaymentFaults: setPaymentFaultsMock }));
vi.mock("@/lib/log", () => ({ logError: vi.fn() }));
vi.mock("next/navigation", () => ({
  unstable_rethrow: (e: unknown) => {
    if (e instanceof Error && e.message.startsWith("NEXT_REDIRECT")) {
      throw e;
    }
  },
}));

const { setPaymentFaultsAction } = await import("./admin-actions");

function form(fields: Record<string, string>) {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) {
    data.set(k, v);
  }
  return data;
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("setPaymentFaultsAction", () => {
  it("검증한 값을 보내고 저장된 설정을 돌려준다", async () => {
    setPaymentFaultsMock.mockResolvedValue({ mode: "DELAY", delayMillis: 4000 });

    await expect(
      setPaymentFaultsAction({}, form({ mode: "DELAY", delayMillis: "4000" })),
    ).resolves.toEqual({ saved: { mode: "DELAY", delayMillis: 4000 } });
    expect(setPaymentFaultsMock).toHaveBeenCalledWith({ mode: "DELAY", delayMillis: 4000 });
  });

  it("잘못된 입력은 보내지 않는다", async () => {
    const state = await setPaymentFaultsAction({}, form({ mode: "DELAY", delayMillis: "99999" }));
    expect(state.error).toBeTruthy();
    expect(setPaymentFaultsMock).not.toHaveBeenCalled();
  });

  it("관리자가 아니면(403) 안내, 로그인 redirect 는 그대로 올린다", async () => {
    setPaymentFaultsMock.mockRejectedValueOnce(new ApiError(403, "Forbidden"));
    await expect(setPaymentFaultsAction({}, form({ mode: "DECLINE" }))).resolves.toEqual({
      error: "관리자만 바꿀 수 있습니다.",
    });

    setPaymentFaultsMock.mockRejectedValueOnce(new Error("NEXT_REDIRECT:/login"));
    await expect(setPaymentFaultsAction({}, form({ mode: "DECLINE" }))).rejects.toThrow(
      "NEXT_REDIRECT:/login",
    );
  });
});
