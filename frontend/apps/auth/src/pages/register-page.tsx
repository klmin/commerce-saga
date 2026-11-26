import { Link, useSearchParams } from "react-router";

import { registerError } from "@/lib/messages";

import { AuthForm, Field } from "./auth-form";

/**
 * 회원가입. auth-service 의 POST signup 이 회원 + UserRegistered(포인트 계정) 를 만들고 바로 로그인한 뒤,
 * 로그인하려던 화면이 있으면 그리로 이어 보낸다. 형식 검사는 브라우저가 먼저 하고, 규칙의 원본은 서버(도메인)다
 */
export function RegisterPage() {
  const [params] = useSearchParams();
  return (
    <AuthForm
      title="회원가입"
      action="signup"
      submitLabel="가입하고 로그인"
      error={registerError(params.get("error"))}
      footer={
        <>
          이미 계정이 있으면{" "}
          <Link to="/signin" className="text-foreground underline underline-offset-4">
            로그인
          </Link>
        </>
      }
    >
      <Field
        id="loginId"
        name="loginId"
        label="아이디"
        autoComplete="username"
        autoFocus
        pattern="[a-z0-9_]{4,30}"
        title="영문 소문자·숫자·밑줄 4~30자"
      />
      <Field
        id="password"
        name="password"
        label="비밀번호"
        type="password"
        autoComplete="new-password"
        minLength={8}
        maxLength={64}
      />
    </AuthForm>
  );
}
