import { Link, useSearchParams } from "react-router";

import { signInError } from "@/lib/messages";

import { AuthForm, Field } from "./auth-form";

/** SAS 의 로그인 화면 (formLogin loginPage). 로그인 처리는 auth-service 의 POST login */
export function SignInPage() {
  const [params] = useSearchParams();
  return (
    <AuthForm
      title="로그인"
      action="login"
      submitLabel="로그인"
      error={signInError(params.get("error"))}
      footer={
        <>
          계정이 없으면{" "}
          <Link to="/register" className="text-foreground underline underline-offset-4">
            회원가입
          </Link>
        </>
      }
    >
      <Field id="username" name="username" label="아이디" autoComplete="username" autoFocus />
      <Field
        id="password"
        name="password"
        label="비밀번호"
        type="password"
        autoComplete="current-password"
      />
    </AuthForm>
  );
}
