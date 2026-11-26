// auth-service 가 돌려보내는 오류 코드 → 화면 문구

/** 로그인 실패면 signin?error, 잠기면 signin?error=locked */
export function signInError(error: string | null): string | null {
  if (error === null) {
    return null;
  }
  return error === "locked"
    ? "로그인 실패가 많아 잠시 잠겼습니다. 잠시 뒤 다시 시도해 주세요."
    : "아이디 또는 비밀번호가 맞지 않습니다.";
}

/** 가입 실패면 register?error=duplicate|invalid */
export function registerError(error: string | null): string | null {
  if (error === null) {
    return null;
  }
  return error === "duplicate"
    ? "이미 사용 중인 아이디입니다."
    : "아이디는 영문 소문자·숫자·밑줄 4~30자, 비밀번호는 8~64자입니다.";
}
