import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { RENEWED_COOKIE, RENEWED_MAX_AGE_SECONDS } from "@/lib/auth/renew";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/auth/session";

import { GET } from "./route";

function req(search: string) {
  return new NextRequest(new URL(`/session/renew${search}`, "http://localhost:3000"), {
    headers: { cookie: `${ACCESS_COOKIE}=stale-at; ${REFRESH_COOKIE}=fam.old` },
  });
}

function location(res: Response): URL {
  return new URL(res.headers.get("location")!);
}

beforeEach(() => {
  vi.stubEnv("COOKIE_SECURE", "true");
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("GET /session/renew", () => {
  it("access 쿠키만 지우고 방금 renew 했다는 표식을 잠깐 심은 뒤 next 로", () => {
    const res = GET(req("?next=%2Forders%3Fbefore%3Dabc"));

    expect(res.status).toBe(303);
    expect(location(res).origin).toBe("http://localhost:3000");
    expect(location(res).pathname + location(res).search).toBe("/orders?before=abc");
    expect(res.cookies.get(ACCESS_COOKIE)).toMatchObject({ value: "", maxAge: 0, path: "/" });
    // refresh 쿠키는 그대로 둔다 (다음 요청에서 proxy 가 이것으로 refresh)
    expect(res.cookies.get(REFRESH_COOKIE)).toBeUndefined();
    expect(res.cookies.get(RENEWED_COOKIE)).toMatchObject({
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: RENEWED_MAX_AGE_SECONDS,
    });
    expect(res.headers.get("cache-control")).toBe("no-store");
  });

  it("next 가 없거나 사이트 밖·renew 자신이면 / 로 (open redirect 방지)", () => {
    for (const search of [
      "",
      "?next=https%3A%2F%2Fevil.example",
      "?next=%2F%2Fevil.example",
      "?next=%2F%5Cevil.example",
      "?next=javascript%3Aalert(1)",
      "?next=%2Fsession%2Frenew%3Fnext%3D%252Forders",
    ]) {
      const res = GET(req(search));
      expect(location(res).href).toBe("http://localhost:3000/");
    }
  });
});
