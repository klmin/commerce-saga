import "server-only";

import { cookies } from "next/headers";

import { serverEnv } from "@/lib/env";

import {
  ACCESS_COOKIE,
  clearedCookies,
  REFRESH_COOKIE,
  rolesFromAccessToken,
  type Session,
  sessionCookies,
  userIdFromAccessToken,
} from "./session";
import { RENEWED_COOKIE } from "./renew";

export async function accessToken(): Promise<string | undefined> {
  return (await cookies()).get(ACCESS_COOKIE)?.value;
}

export async function refreshToken(): Promise<string | undefined> {
  return (await cookies()).get(REFRESH_COOKIE)?.value;
}

/** 화면 표시용 로그인 사용자 id (토큰 서명 검증은 백엔드가 한다) */
export async function currentUserId(): Promise<number | undefined> {
  return userIdFromAccessToken(await accessToken());
}

/** 관리자 메뉴를 보일지 (권한 검사는 백엔드가 한다) */
export async function isAdmin(): Promise<boolean> {
  return rolesFromAccessToken(await accessToken()).includes("ADMIN");
}

/** Server Action 에서만 부른다 (쿠키는 응답을 보내기 전에만 바꿀 수 있다) */
export async function saveSession(session: Session): Promise<void> {
  const store = await cookies();
  for (const c of sessionCookies(session, serverEnv().COOKIE_SECURE)) {
    store.set(c.name, c.value, c.options);
  }
  // 새로 로그인했으니 "방금 renew 했음" 표식을 지운다 (남아 있으면 로그인 직후 401 이 renew 없이 로그인 화면으로 간다)
  store.delete(RENEWED_COOKIE);
}

/** Server Action 에서만 부른다 */
export async function clearSession(): Promise<void> {
  const store = await cookies();
  for (const c of clearedCookies(serverEnv().COOKIE_SECURE)) {
    store.set(c.name, c.value, c.options);
  }
}
