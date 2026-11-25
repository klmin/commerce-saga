import { type NextRequest, NextResponse } from "next/server";

import { ApiError } from "@/lib/api/client";
import { refresh } from "@/lib/auth/backend";
import { clientInfoFrom } from "@/lib/auth/client";
import { refreshOnce } from "@/lib/auth/refresh-once";
import { RENEW_PATH, REQUEST_PATH_HEADER } from "@/lib/auth/renew";
import {
  ACCESS_COOKIE,
  clearedCookies,
  type CookieToSet,
  REFRESH_COOKIE,
  sessionCookies,
} from "@/lib/auth/session";
import { serverEnv } from "@/lib/env";
import { logError } from "@/lib/log";

// 로그인 없이 볼 수 있는 화면
const PUBLIC_PATHS = ["/login", "/signup"];

function isPublic(pathname: string): boolean {
  return PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

function toLogin(request: NextRequest): NextResponse {
  const url = new URL("/login", request.url);
  const next = request.nextUrl.pathname + request.nextUrl.search;
  if (next !== "/") {
    url.searchParams.set("next", next);
  }
  return NextResponse.redirect(url);
}

/**
 * 서버 컴포넌트·Server Action 이 백엔드 401 뒤 돌아올 경로를 알 수 있게 지금 경로를 요청 헤더로 넘긴다.
 * 브라우저가 같은 이름으로 보낸 값은 덮어쓴다 (쓰는 쪽도 safeNextPath 로 다시 검사한다)
 */
function passThrough(request: NextRequest): NextResponse {
  const url = request.nextUrl.clone();
  url.searchParams.delete("_rsc");
  const headers = new Headers(request.headers);
  headers.set(REQUEST_PATH_HEADER, url.pathname + url.search);
  return NextResponse.next({ request: { headers } });
}

function withCookies(response: NextResponse, cookies: CookieToSet[]): NextResponse {
  for (const c of cookies) {
    response.cookies.set(c.name, c.value, c.options);
  }
  return response;
}

/**
 * 화면 요청마다 세션을 본다 (화면 접근만 판단한다. 권한·소유권은 백엔드가 토큰으로 검사한다).
 * - access 쿠키가 있으면 그대로 통과
 * - access 는 만료됐고 refresh 쿠키가 있으면 백엔드 refresh → 새 쿠키를 응답에 심고, 이번 요청에도 새 access 를 실어 보낸다
 * - refresh 가 거절되면(만료·재사용 탐지) 쿠키를 지우고 로그인 화면으로
 * - 둘 다 없으면 보호된 화면은 로그인 화면으로
 * - access 가 있어도 백엔드가 401 이면(서명 키 교체 등) 화면이 RENEW_PATH 로 보내 access 쿠키를 지운다 → 다음 요청에서 위 refresh 경로
 */
export async function proxy(request: NextRequest): Promise<NextResponse> {
  const pathname = request.nextUrl.pathname;
  const accessToken = request.cookies.get(ACCESS_COOKIE)?.value;
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;

  // renew route 가 쿠키를 직접 다룬다 (여기서 refresh 하면 방금 받은 access 를 renew 가 지운다)
  if (pathname === RENEW_PATH) {
    return NextResponse.next();
  }
  if (accessToken) {
    return passThrough(request);
  }
  if (!refreshToken) {
    return isPublic(pathname) ? passThrough(request) : toLogin(request);
  }

  const secure = serverEnv().COOKIE_SECURE;
  try {
    const client = clientInfoFrom(request.headers);
    const session = await refreshOnce(refreshToken, (token) => refresh(token, client));
    const cookies = sessionCookies(session, secure);
    // 이번 요청을 처리하는 서버 컴포넌트·Server Action 도 새 access token 을 보게 요청 쿠키를 바꿔 넘긴다
    for (const c of cookies) {
      request.cookies.set(c.name, c.value);
    }
    return withCookies(passThrough(request), cookies);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      const response = isPublic(pathname) ? passThrough(request) : toLogin(request);
      return withCookies(response, clearedCookies(secure));
    }
    // 백엔드에 닿지 않음: 쿠키는 그대로 두고 화면이 오류를 보여 주게 한다 (다음 요청에서 다시 refresh)
    logError("session refresh failed", e);
    return passThrough(request);
  }
}

export const config = {
  // 정적 파일·이미지 최적화·파비콘은 세션과 상관없다
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
