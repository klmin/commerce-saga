// 세션 쿠키 (ADR 0008, 0011). Next 서버가 백엔드에서 받은 토큰을 브라우저에 httpOnly 쿠키로만 둔다.
// 브라우저 JS(localStorage)는 토큰을 볼 수 없다. proxy.ts 와 Server Action 이 함께 쓰므로 next/headers 에 의존하지 않는다

export const ACCESS_COOKIE = "cs_access";
export const REFRESH_COOKIE = "cs_refresh";

// access token 쿠키는 토큰 만료보다 조금 일찍 사라지게 해서, 만료 직전 토큰으로 백엔드를 부르지 않고 proxy 가 미리 refresh 한다
export const ACCESS_EARLY_EXPIRY_SECONDS = 30;

/**
 * 백엔드에서 받은 토큰.
 * refreshToken 이 없으면 백엔드가 회전하지 않은 응답(동시 refresh 유예)이라 refresh 쿠키는 그대로 둔다.
 */
export type Session = {
  accessToken: string;
  accessMaxAge: number;
  refreshToken?: string;
  refreshMaxAge?: number;
};

export type CookieToSet = {
  name: string;
  value: string;
  options: {
    httpOnly: true;
    secure: boolean;
    sameSite: "lax";
    path: "/";
    maxAge: number;
  };
};

function cookie(name: string, value: string, maxAge: number, secure: boolean): CookieToSet {
  return { name, value, options: { httpOnly: true, secure, sameSite: "lax", path: "/", maxAge } };
}

export function sessionCookies(session: Session, secure: boolean): CookieToSet[] {
  const cookies = [
    cookie(
      ACCESS_COOKIE,
      session.accessToken,
      Math.max(1, session.accessMaxAge - ACCESS_EARLY_EXPIRY_SECONDS),
      secure,
    ),
  ];
  if (session.refreshToken && session.refreshMaxAge) {
    cookies.push(cookie(REFRESH_COOKIE, session.refreshToken, session.refreshMaxAge, secure));
  }
  return cookies;
}

export function clearedCookies(secure: boolean): CookieToSet[] {
  return [cookie(ACCESS_COOKIE, "", 0, secure), cookie(REFRESH_COOKIE, "", 0, secure)];
}

/** 백엔드 응답 Set-Cookie 에서 refresh_token 값과 Max-Age 를 꺼낸다. 없거나 비었으면 undefined */
export function refreshTokenFrom(headers: Headers): { value: string; maxAge: number } | undefined {
  for (const raw of headers.getSetCookie()) {
    const [pair, ...attributes] = raw.split(";").map((p) => p.trim());
    const eq = pair.indexOf("=");
    if (eq < 0 || pair.slice(0, eq) !== "refresh_token") {
      continue;
    }
    const value = pair.slice(eq + 1);
    const maxAgeAttr = attributes.find((a) => a.toLowerCase().startsWith("max-age="));
    const maxAge = maxAgeAttr ? Number(maxAgeAttr.slice("max-age=".length)) : NaN;
    if (!value || !Number.isFinite(maxAge) || maxAge <= 0) {
      return undefined;
    }
    return { value, maxAge };
  }
  return undefined;
}

/**
 * access token 의 sub(userId). 화면 표시용으로만 쓴다: 서명은 백엔드(Gateway·서비스)가 검증하고,
 * 이 값으로 권한을 판단하지 않는다
 */
export function userIdFromAccessToken(token: string | undefined): number | undefined {
  const payload = token?.split(".")[1];
  if (!payload) {
    return undefined;
  }
  try {
    const json = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      sub?: unknown;
    };
    const id = Number(json.sub);
    return Number.isSafeInteger(id) && id > 0 ? id : undefined;
  } catch {
    return undefined;
  }
}
