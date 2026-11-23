import "server-only";

import { z } from "zod";

import { ApiError, toApiError } from "@/lib/api/client";
import { serverEnv } from "@/lib/env";

import { refreshTokenFrom, type Session } from "./session";

// auth-service 는 쿠키로 오는 요청 중 이 헤더가 없는 상태 변경 요청을 막는다 (CSRF, ADR 0008)
const CSRF_HEADER = { "X-Requested-With": "commerce-saga-frontend" };
const TIMEOUT_MS = 10_000;

const tokenResponse = z.object({
  accessToken: z.string().min(1),
  expiresIn: z.number().int().nonnegative(),
});

const signUpResponse = z.object({ userId: z.number().int().positive() });

async function call(path: string, init: RequestInit): Promise<Response> {
  const res = await fetch(new URL(path, serverEnv().BACKEND_URL), {
    ...init,
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

export async function signUp(loginId: string, password: string): Promise<number> {
  const res = await call("/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ loginId, password }),
  });
  return signUpResponse.parse(await res.json()).userId;
}

/** @param userAgent 로그인 기기 목록에 보일 이름 (브라우저의 User-Agent) */
export async function login(
  loginId: string,
  password: string,
  userAgent: string | undefined,
): Promise<Session> {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (userAgent) {
    headers.set("User-Agent", userAgent.slice(0, 200));
  }
  const res = await call("/auth/login", {
    method: "POST",
    headers,
    body: JSON.stringify({ loginId, password }),
  });
  const session = await toSession(res);
  if (!session.refreshToken) {
    throw new ApiError(502, "로그인 응답에 refresh token 이 없습니다.");
  }
  return session;
}

/** refresh token 이 만료·폐기·재사용이면 ApiError(401) */
export async function refresh(refreshToken: string): Promise<Session> {
  const res = await call("/auth/refresh", {
    method: "POST",
    headers: { Cookie: `refresh_token=${refreshToken}`, ...CSRF_HEADER },
  });
  return toSession(res);
}

/** 이미 없는 세션이어도 성공으로 본다 */
export async function logout(refreshToken: string): Promise<void> {
  await call("/auth/logout", {
    method: "POST",
    headers: { Cookie: `refresh_token=${refreshToken}`, ...CSRF_HEADER },
  });
}
