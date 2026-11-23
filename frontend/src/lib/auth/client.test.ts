import { describe, expect, it } from "vitest";

import { clientInfoFrom } from "./client";

describe("clientInfoFrom", () => {
  it("X-Forwarded-For 의 맨 오른쪽(바로 앞 프록시나 Next 가 본 주소)을 브라우저 IP 로", () => {
    const headers = new Headers({
      "x-forwarded-for": "1.2.3.4, 198.51.100.9",
      "user-agent": "Mozilla/5.0",
    });
    expect(clientInfoFrom(headers)).toEqual({ ip: "198.51.100.9", userAgent: "Mozilla/5.0" });
    expect(clientInfoFrom(new Headers({ "x-forwarded-for": "::1" })).ip).toBe("::1");
  });

  it("IP 모양이 아니거나 없으면 보내지 않는다 (Gateway 는 연결 주소로)", () => {
    expect(clientInfoFrom(new Headers()).ip).toBeUndefined();
    expect(
      clientInfoFrom(new Headers({ "x-forwarded-for": "1.2.3.4, <script>" })).ip,
    ).toBeUndefined();
    expect(clientInfoFrom(new Headers({ "x-forwarded-for": "unknown" })).ip).toBeUndefined();
  });
});
