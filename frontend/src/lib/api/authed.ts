import "server-only";

import { redirect } from "next/navigation";

import { accessToken } from "@/lib/auth/server-session";

import { ApiError, request } from "./client";

/**
 * 로그인한 사용자로 Gateway(/api/**)를 부른다. access token 을 Authorization: Bearer 로 붙인다.
 * 토큰이 없거나 백엔드가 401 이면 로그인 화면으로 보낸다 (refresh 는 proxy.ts 가 화면 요청 전에 한다)
 */
export async function authedRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await accessToken();
  if (!token) {
    redirect("/login");
  }
  const headers = new Headers(init?.headers);
  headers.set("Authorization", `Bearer ${token}`);
  try {
    return await request<T>(path, { ...init, headers, cache: "no-store" });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      redirect("/login");
    }
    throw e;
  }
}
