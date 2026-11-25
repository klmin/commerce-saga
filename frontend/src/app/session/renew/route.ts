import { type NextRequest, NextResponse } from "next/server";

import { RENEWED_COOKIE, RENEWED_MAX_AGE_SECONDS, safeReturnPath } from "@/lib/auth/renew";
import { ACCESS_COOKIE } from "@/lib/auth/session";
import { serverEnv } from "@/lib/env";

/**
 * 백엔드가 401 을 준 뒤 refresh 를 한 번 시도하게 한다 (흐름은 lib/auth/renew.ts).
 * access 쿠키만 지우고 next 로 보내면, 다음 요청에서 proxy 가 refresh 쿠키로 새 access token 을 받는다.
 * refresh 쿠키가 없거나 거절되면 proxy 가 로그인 화면으로 보낸다. proxy 는 이 경로에서 아무것도 하지 않는다
 *
 * RSC 요청(클라이언트 라우터 이동, Server Action redirect 를 Next 서버가 미리 따라가는 fetch)에는 쿠키를 바꾸지 않고
 * RSC 가 아닌 응답을 준다. 그러면 브라우저가 이 주소로 문서 이동을 해서 아래 Set-Cookie 를 직접 받는다.
 * (Server Action 의 redirect 를 서버가 따라갈 때는 응답 Set-Cookie 를 브라우저에 넘기지 않아서, 그대로 redirect 하면
 * 옛 access 로 화면을 다시 그리다 401 → 같은 renew 주소에 멈춘다)
 */
export function GET(request: NextRequest): NextResponse {
  if (request.headers.get("rsc") === "1") {
    return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  }
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
