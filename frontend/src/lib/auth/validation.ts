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

// 사이트 밖으로 나가는지 보려고 붙여 보는 가짜 origin
const SELF_ORIGIN = "http://self.invalid";

/**
 * 로그인 뒤 돌아갈 경로. 우리 사이트 안의 경로만 허용한다 (open redirect 방지).
 * URL 파서는 탭·줄바꿈을 지우고 역슬래시를 `/` 로 읽어 `/\t/evil.example` 같은 값이 외부 주소가 되므로,
 * 제어문자·공백·역슬래시를 거부하고 파싱한 결과의 origin 까지 본다.
 */
export function safeNextPath(next: string | null | undefined): string {
  if (!next || !next.startsWith("/") || /[\u0000- \u007f\\]/.test(next)) {
    return "/";
  }
  let url: URL;
  try {
    url = new URL(next, SELF_ORIGIN);
  } catch {
    return "/";
  }
  if (url.origin !== SELF_ORIGIN) {
    return "/";
  }
  return url.pathname + url.search + url.hash;
}
