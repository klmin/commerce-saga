import "server-only";

import { z } from "zod";

import { ApiError, toApiError } from "@/lib/api/client";
import { serverEnv } from "@/lib/env";

import type { ClientInfo } from "./client";
import { refreshTokenFrom, type Session } from "./session";

// auth-service 는 쿠키로 오는 요청 중 이 헤더가 없는 상태 변경 요청을 막는다 (CSRF, ADR 0008)
const CSRF_HEADER = { "X-Requested-With": "commerce-saga-frontend" };
const TIMEOUT_MS = 10_000;

const tokenResponse = z.object({
  accessToken: z.string().min(1),
  expiresIn: z.number().int().nonnegative(),
});

const signUpResponse = z.object({ userId: z.number().int().positive() });

async function call(path: string, init: RequestInit, client: ClientInfo): Promise<Response> {
  const headers = new Headers(init.headers);
  // Gateway 의 /auth/** rate limit 이 Next 서버 주소 하나가 아니라 브라우저마다 걸리게 한다 (ADR 0011)
  if (client.ip) {
    headers.set("X-Forwarded-For", client.ip);
  }
  const res = await fetch(new URL(path, serverEnv().BACKEND_URL), {
    ...init,
    headers,
    signal: AbortSignal.timeout(TIMEOUT_MS),
    cache: "no-store",
  });
  if (!res.ok) {
    throw await toApiError(res);
  }
  return res;
}

async function toSession(res: Response): Promise<Session> {
  const body = tokenResponse.parse(await res.json());
  const refresh = refreshTokenFrom(res.headers);
  return {
    accessToken: body.accessToken,
    accessMaxAge: body.expiresIn,
    refreshToken: refresh?.value,
    refreshMaxAge: refresh?.maxAge,
  };
}

export async function signUp(
  loginId: string,
  password: string,
  client: ClientInfo,
): Promise<number> {
  const res = await call(
    "/auth/signup",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ loginId, password }),
    },
    client,
  );
  return signUpResponse.parse(await res.json()).userId;
}

/** client.userAgent 는 로그인 기기 목록에 보일 이름 */
export async function login(
  loginId: string,
  password: string,
  client: ClientInfo,
): Promise<Session> {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (client.userAgent) {
    headers.set("User-Agent", client.userAgent.slice(0, 200));
  }
  const res = await call(
    "/auth/login",
    { method: "POST", headers, body: JSON.stringify({ loginId, password }) },
    client,
  );
  const session = await toSession(res);
  if (!session.refreshToken) {
    throw new ApiError(502, "로그인 응답에 refresh token 이 없습니다.");
  }
  return session;
}

/** refresh token 이 만료·폐기·재사용이면 ApiError(401) */
export async function refresh(refreshToken: string, client: ClientInfo): Promise<Session> {
  const res = await call(
    "/auth/refresh",
    { method: "POST", headers: { Cookie: `refresh_token=${refreshToken}`, ...CSRF_HEADER } },
    client,
  );
  return toSession(res);
}

const loginSession = z.object({
  sessionId: z.string(),
  agent: z.string().nullable(),
  createdAt: z.string(),
  lastUsedAt: z.string(),
  expiresAt: z.string(),
  current: z.boolean(),
});

export type LoginSession = z.infer<typeof loginSession>;

function bearer(accessToken: string): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` };
}

/**
 * 내 로그인 기기(refresh token family) 목록. refresh token 을 같이 보내면 이 기기가 current 로 표시된다.
 * GET 이라 쿠키가 실려도 CSRF 헤더가 필요 없다. access token 이 만료·위조면 ApiError(401)
 */
export async function listSessions(
  accessToken: string,
  refreshToken: string | undefined,
  client: ClientInfo,
): Promise<LoginSession[]> {
  const headers: Record<string, string> = bearer(accessToken);
  if (refreshToken) {
    headers.Cookie = `refresh_token=${refreshToken}`;
  }
  const res = await call("/auth/sessions", { headers }, client);
  return z.array(loginSession).parse(await res.json());
}

/** 그 기기 로그아웃. 남의 세션·없는 세션이면 ApiError(404) (auth-service 가 토큰 sub 와 소유자를 비교) */
export async function revokeSession(
  accessToken: string,
  sessionId: string,
  client: ClientInfo,
): Promise<void> {
  await call(
    `/auth/sessions/${encodeURIComponent(sessionId)}`,
    { method: "DELETE", headers: bearer(accessToken) },
    client,
  );
}

/** 모든 기기 로그아웃 (이 기기 포함). 쿠키 없이 Bearer 로만 보낸다 */
export async function logoutAll(accessToken: string, client: ClientInfo): Promise<void> {
  await call("/auth/logout-all", { method: "POST", headers: bearer(accessToken) }, client);
}

/** 이미 없는 세션이어도 성공으로 본다 */
export async function logout(refreshToken: string, client: ClientInfo): Promise<void> {
  await call(
    "/auth/logout",
    { method: "POST", headers: { Cookie: `refresh_token=${refreshToken}`, ...CSRF_HEADER } },
    client,
  );
}
