// 백엔드(auth-service) 규칙과 같다 (ADR 0008). 백엔드가 최종 검사하고, 여기서는 왕복 없이 바로 알려 주려고 한 번 더 본다
export const LOGIN_ID_PATTERN = /^[a-zA-Z0-9_]{4,30}$/;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 64;

export function validateSignUp(loginId: string, password: string): string | undefined {
  if (!LOGIN_ID_PATTERN.test(loginId)) {
    return "아이디는 영문·숫자·밑줄 4~30자로 입력해 주세요.";
  }
  if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
    return `비밀번호는 ${PASSWORD_MIN}~${PASSWORD_MAX}자로 입력해 주세요.`;
  }
  if (new TextEncoder().encode(password).length > 72) {
    return "비밀번호가 너무 깁니다.";
  }
  return undefined;
}

/** 로그인 뒤 돌아갈 경로. 우리 사이트 안의 경로만 허용한다 (open redirect 방지) */
export function safeNextPath(next: string | null | undefined): string {
  if (!next || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return "/";
  }
  return next;
}
