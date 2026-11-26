import "server-only";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";

import { loginPath, RENEWED_COOKIE, renewPath, REQUEST_PATH_HEADER, safeReturnPath } from "./renew";

async function currentPath(): Promise<string> {
  return safeReturnPath((await headers()).get(REQUEST_PATH_HEADER));
}

/** 로그인하지 않았다 (access·refresh 쿠키가 없어 proxy 도 refresh 하지 못했다): 로그인 화면으로 */
export async function redirectToLogin(): Promise<never> {
  redirect(loginPath(await currentPath()));
}

/**
 * 백엔드가 401 (서명 키 교체 등으로 access token 이 무효). 서버 컴포넌트는 쿠키를 바꿀 수 없으니 renew route 로 보내
 * refresh 를 한 번 시도한다. 방금 renew 했는데도 401 이면 로그인 화면으로
 */
export async function redirectAfterUnauthorized(): Promise<never> {
  const path = await currentPath();
  const renewed = (await cookies()).has(RENEWED_COOKIE);
  redirect(renewed ? loginPath(path) : renewPath(path));
}
