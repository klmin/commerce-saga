"use server";

import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import * as auth from "@/lib/auth/backend";
import { clientInfoFrom } from "@/lib/auth/client";
import { clearSession, refreshToken, saveSession } from "@/lib/auth/server-session";
import { safeNextPath, validateSignUp } from "@/lib/auth/validation";
import { logError } from "@/lib/log";

export type AuthFormState = {
  error?: string;
  // 실패했을 때 아이디를 다시 채운다 (비밀번호는 돌려주지 않는다)
  loginId?: string;
};

const UNREACHABLE = "서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.";

function field(formData: FormData, name: string): string {
  return String(formData.get(name) ?? "");
}

export async function loginAction(
  _prev: AuthFormState,
  formData: FormData,
): Promise<AuthFormState> {
  const loginId = field(formData, "loginId").trim();
  const password = field(formData, "password");
  if (!loginId || !password) {
    return { error: "아이디와 비밀번호를 입력해 주세요.", loginId };
  }
  try {
    await saveSession(await auth.login(loginId, password, clientInfoFrom(await headers())));
  } catch (e) {
    if (e instanceof ApiError) {
      // 401(아이디·비밀번호 불일치), 429(잠금)는 백엔드 메시지를 그대로 보여 준다
      return { error: e.message, loginId };
    }
    logError("loginAction failed", e);
    return { error: UNREACHABLE, loginId };
  }
  redirect(safeNextPath(field(formData, "next")));
}

export async function signUpAction(
  _prev: AuthFormState,
  formData: FormData,
): Promise<AuthFormState> {
  const loginId = field(formData, "loginId").trim();
  const password = field(formData, "password");
  const invalid = validateSignUp(loginId, password);
  if (invalid) {
    return { error: invalid, loginId };
  }
  if (password !== field(formData, "passwordConfirm")) {
    return { error: "비밀번호 확인이 일치하지 않습니다.", loginId };
  }
  try {
    const client = clientInfoFrom(await headers());
    await auth.signUp(loginId, password, client);
    // 가입하면 바로 로그인한다. 포인트 계정(가입 축하 1,000P)은 auth.events 로 몇 초 안에 생긴다 (시나리오 13)
    await saveSession(await auth.login(loginId, password, client));
  } catch (e) {
    if (e instanceof ApiError) {
      return { error: e.status === 409 ? "이미 쓰고 있는 아이디입니다." : e.message, loginId };
    }
    logError("signUpAction failed", e);
    return { error: UNREACHABLE, loginId };
  }
  redirect("/");
}

export async function logoutAction(): Promise<void> {
  const token = await refreshToken();
  if (token) {
    try {
      await auth.logout(token, clientInfoFrom(await headers()));
    } catch (e) {
      // 백엔드 세션은 만료로 정리된다. 브라우저 쿠키는 어쨌든 지운다
      logError("logoutAction failed", e);
    }
  }
  await clearSession();
  redirect("/login");
}
