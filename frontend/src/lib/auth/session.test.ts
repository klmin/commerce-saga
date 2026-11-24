import { describe, expect, it } from "vitest";

import {
  ACCESS_COOKIE,
  clearedCookies,
  REFRESH_COOKIE,
  refreshTokenFrom,
  rolesFromAccessToken,
  sessionCookies,
  userIdFromAccessToken,
} from "./session";

describe("sessionCookies", () => {
  it("httpOnly·SameSite=Lax 쿠키로, access 는 만료보다 30초 일찍 사라진다", () => {
    const cookies = sessionCookies(
      { accessToken: "at", accessMaxAge: 900, refreshToken: "fam.secret", refreshMaxAge: 1209600 },
      true,
    );

    expect(cookies).toEqual([
      {
        name: ACCESS_COOKIE,
        value: "at",
        options: { httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: 870 },
      },
      {
        name: REFRESH_COOKIE,
        value: "fam.secret",
        options: { httpOnly: true, secure: true, sameSite: "lax", path: "/", maxAge: 1209600 },
      },
    ]);
  });

  it("회전하지 않은 응답(refresh token 없음)이면 refresh 쿠키는 건드리지 않는다", () => {
    const cookies = sessionCookies({ accessToken: "at", accessMaxAge: 900 }, false);

    expect(cookies.map((c) => c.name)).toEqual([ACCESS_COOKIE]);
    expect(cookies[0].options.secure).toBe(false);
  });

  it("지우기는 두 쿠키 모두 maxAge 0", () => {
    expect(clearedCookies(true).map((c) => [c.name, c.value, c.options.maxAge])).toEqual([
      [ACCESS_COOKIE, "", 0],
      [REFRESH_COOKIE, "", 0],
    ]);
  });
});

describe("refreshTokenFrom", () => {
  it("백엔드 Set-Cookie 에서 refresh_token 값과 Max-Age 를 꺼낸다", () => {
    const headers = new Headers();
    headers.append("Set-Cookie", "other=x; Path=/");
    headers.append(
      "Set-Cookie",
      "refresh_token=fam.secret; Path=/auth; Max-Age=1209600; Expires=Mon, 07 Dec 2026 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax",
    );

    expect(refreshTokenFrom(headers)).toEqual({ value: "fam.secret", maxAge: 1209600 });
  });

  it("없거나 지우는 쿠키(Max-Age=0)면 undefined", () => {
    expect(refreshTokenFrom(new Headers())).toBeUndefined();
    const cleared = new Headers({ "Set-Cookie": "refresh_token=; Path=/auth; Max-Age=0" });
    expect(refreshTokenFrom(cleared)).toBeUndefined();
  });
});

describe("userIdFromAccessToken", () => {
  const token = (payload: object) =>
    `h.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.s`;

  it("sub 를 숫자로", () => {
    expect(userIdFromAccessToken(token({ sub: "1000" }))).toBe(1000);
  });

  it("없거나 이상하면 undefined", () => {
    expect(userIdFromAccessToken(undefined)).toBeUndefined();
    expect(userIdFromAccessToken("garbage")).toBeUndefined();
    expect(userIdFromAccessToken(token({ sub: "abc" }))).toBeUndefined();
    expect(userIdFromAccessToken("h.!!!.s")).toBeUndefined();
    expect(
      userIdFromAccessToken(`h.${Buffer.from("null").toString("base64url")}.s`),
    ).toBeUndefined();
  });
});

describe("rolesFromAccessToken", () => {
  const token = (payload: object) =>
    `h.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.s`;

  it("roles 클레임의 문자열만", () => {
    expect(rolesFromAccessToken(token({ roles: ["ADMIN", "USER"] }))).toEqual(["ADMIN", "USER"]);
    expect(rolesFromAccessToken(token({ roles: ["USER", 1, null] }))).toEqual(["USER"]);
  });

  it("없거나 이상하면 빈 목록", () => {
    expect(rolesFromAccessToken(undefined)).toEqual([]);
    expect(rolesFromAccessToken(token({ roles: "ADMIN" }))).toEqual([]);
    expect(rolesFromAccessToken("garbage")).toEqual([]);
  });
});
