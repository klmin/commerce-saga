import "server-only";

import { accessToken } from "@/lib/auth/server-session";
import { redirectAfterUnauthorized, redirectToLogin } from "@/lib/auth/unauthorized";

import { ApiError, request } from "./client";

/**
 * 로그인한 사용자로 Gateway(/api/**)를 부른다. access token 을 Authorization: Bearer 로 붙인다.
 * 토큰이 없으면 로그인 화면으로. 백엔드가 401 이면(서명 키 교체 등) refresh 를 한 번 시도하고, 그래도 401 이면 로그인 화면으로
 * (refresh 는 proxy.ts 가 화면 요청 전에 한다. 흐름은 lib/auth/renew.ts)
 */
export async function authedRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await accessToken();
  if (!token) {
    return redirectToLogin();
  }
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  try {
    return await request<T>(path, { ...init, headers, cache: "no-store" });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return redirectAfterUnauthorized();
    }
    throw e;
  }
}
