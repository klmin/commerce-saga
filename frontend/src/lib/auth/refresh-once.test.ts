import { afterEach, describe, expect, it, vi } from "vitest";

import { refreshOnce, resetRefreshOnce } from "./refresh-once";
import type { Session } from "./session";

afterEach(() => {
  resetRefreshOnce();
});

const session: Session = {
  accessToken: "new",
  accessMaxAge: 900,
  refreshToken: "fam.new",
  refreshMaxAge: 100,
};

describe("refreshOnce", () => {
  it("같은 refresh token 의 동시 요청은 백엔드를 한 번만 부르고 같은 결과를 나눠 쓴다", async () => {
    let resolve!: (s: Session) => void;
    const doRefresh = vi.fn(() => new Promise<Session>((r) => (resolve = r)));

    const a = refreshOnce("fam.old", doRefresh);
    const b = refreshOnce("fam.old", doRefresh);
    resolve(session);

    await expect(a).resolves.toBe(session);
    await expect(b).resolves.toBe(session);
    // 끝난 직후 옛 쿠키로 온 요청도 같은 결과 (다시 부르면 백엔드가 재사용으로 본다)
    await expect(refreshOnce("fam.old", doRefresh)).resolves.toBe(session);
    expect(doRefresh).toHaveBeenCalledTimes(1);
  });

  it("다른 refresh token 은 따로 부른다", async () => {
    const doRefresh = vi.fn(async () => session);

    await refreshOnce("a", doRefresh);
    await refreshOnce("b", doRefresh);

    expect(doRefresh).toHaveBeenCalledTimes(2);
  });

  it("실패는 남겨 두지 않아 다음 요청이 다시 시도한다", async () => {
    const doRefresh = vi
      .fn<(t: string) => Promise<Session>>()
      .mockRejectedValueOnce(new Error("down"))
      .mockResolvedValueOnce(session);

    await expect(refreshOnce("fam", doRefresh)).rejects.toThrow("down");
    await expect(refreshOnce("fam", doRefresh)).resolves.toBe(session);
  });
});
