"use server";

import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import * as auth from "@/lib/auth/backend";
import { clientInfoFrom } from "@/lib/auth/client";
import { accessToken, clearSession } from "@/lib/auth/server-session";
import { redirectAfterUnauthorized, redirectToLogin } from "@/lib/auth/unauthorized";
import { logError } from "@/lib/log";

const SESSION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

async function requireAccessToken(): Promise<string> {
  const token = await accessToken();
  if (!token) {
    return redirectToLogin();
  }
  return token;
}

/**
 * 다른 기기 로그아웃. 소유자 검사는 auth-service 가 토큰 sub 로 한다(남의 세션 id 면 404).
 * 이미 없는 세션(404)은 목록만 다시 그린다
 */
export async function revokeSessionAction(formData: FormData): Promise<void> {
  const sessionId = String(formData.get("sessionId") ?? "");
  if (!SESSION_ID.test(sessionId)) {
    redirect("/sessions?error=invalid");
  }
  const token = await requireAccessToken();
  let failed = false;
  try {
    await auth.revokeSession(token, sessionId, clientInfoFrom(await headers()));
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return redirectAfterUnauthorized();
    }
    if (!(e instanceof ApiError && e.status === 404)) {
      logError("revokeSessionAction failed", e);
      failed = true;
    }
  }
  redirect(failed ? "/sessions?error=failed" : "/sessions");
}

/** 모든 기기 로그아웃 (이 기기 포함). 성공하면 이 브라우저 쿠키도 지우고 로그인 화면으로 */
export async function logoutAllAction(): Promise<void> {
  const token = await requireAccessToken();
  try {
    await auth.logoutAll(token, clientInfoFrom(await headers()));
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401)) {
      logError("logoutAllAction failed", e);
      redirect("/sessions?error=failed");
    }
    // 401: 이미 토큰이 무효다. 이 브라우저 쿠키만 지운다
  }
  await clearSession();
  redirect("/login");
}
