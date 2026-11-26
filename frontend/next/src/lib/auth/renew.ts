// 백엔드 401 뒤 refresh 를 한 번 시도하는 흐름 (ADR 0011). proxy.ts·route handler·서버 컴포넌트가 함께 쓰므로 next/headers 에 의존하지 않는다
//
// 1. authedRequest 가 401 을 받으면 RENEW_PATH?next=<지금 경로> 로 보낸다
// 2. RENEW_PATH(route handler)는 access 쿠키만 지우고, "방금 renew 했음" 쿠키를 잠깐 심고 next 로 보낸다
// 3. 다음 요청에서 proxy 가 "access 없음 + refresh 있음" 으로 refresh 한다. 거절되면 proxy 가 로그인 화면으로
// 4. refresh 뒤에도 401 이면(계정 삭제 등) 표식이 있으니 로그인 화면으로 (renew → 화면 → 401 → renew 반복 방지)

import { safeNextPath } from "./validation";

export const RENEW_PATH = "/session/renew";

/** "방금 renew 했음" 표식. 값은 상관없다 */
export const RENEWED_COOKIE = "cs_renewed";
export const RENEWED_MAX_AGE_SECONDS = 30;

/** proxy 가 서버 컴포넌트·Server Action 에 넘기는 지금 화면 경로 (pathname + search). 브라우저가 보낸 값은 proxy 가 덮어쓴다 */
export const REQUEST_PATH_HEADER = "x-cs-request-path";

/** 돌아갈 경로. 사이트 안 경로만, renew 자신은 "/" 로 (open redirect·자기 자신으로 돌기 방지) */
export function safeReturnPath(next: string | null | undefined): string {
  const path = safeNextPath(next);
  return path === RENEW_PATH ||
    path.startsWith(`${RENEW_PATH}?`) ||
    path.startsWith(`${RENEW_PATH}/`)
    ? "/"
    : path;
}

export function loginPath(next: string): string {
  const path = safeReturnPath(next);
  return path === "/" ? "/login" : `/login?next=${encodeURIComponent(path)}`;
}

export function renewPath(next: string): string {
  const path = safeReturnPath(next);
  return path === "/" ? RENEW_PATH : `${RENEW_PATH}?next=${encodeURIComponent(path)}`;
}
