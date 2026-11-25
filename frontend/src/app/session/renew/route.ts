import { type NextRequest, NextResponse } from "next/server";

import { RENEWED_COOKIE, RENEWED_MAX_AGE_SECONDS, safeReturnPath } from "@/lib/auth/renew";
import { ACCESS_COOKIE } from "@/lib/auth/session";
import { serverEnv } from "@/lib/env";

/**
 * 백엔드가 401 을 준 뒤 refresh 를 한 번 시도하게 한다 (흐름은 lib/auth/renew.ts).
 * access 쿠키만 지우고 next 로 보내면, 다음 요청에서 proxy 가 refresh 쿠키로 새 access token 을 받는다.
 * refresh 쿠키가 없거나 거절되면 proxy 가 로그인 화면으로 보낸다. proxy 는 이 경로에서 아무것도 하지 않는다
 */
export function GET(request: NextRequest): NextResponse {
  const next = safeReturnPath(request.nextUrl.searchParams.get("next"));
  const secure = serverEnv().COOKIE_SECURE;
  const response = NextResponse.redirect(new URL(next, request.url), 303);
  response.headers.set("Cache-Control", "no-store");
  response.cookies.set(ACCESS_COOKIE, "", {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  response.cookies.set(RENEWED_COOKIE, "1", {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: RENEWED_MAX_AGE_SECONDS,
  });
  return response;
}
