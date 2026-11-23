"use client";

import Link from "next/link";
import { useActionState, useId } from "react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

import type { AuthFormState } from "./auth-actions";

type Props = {
  mode: "login" | "signup";
  action: (prev: AuthFormState, formData: FormData) => Promise<AuthFormState>;
  next?: string;
};

const initialState: AuthFormState = {};

// 로그인·회원가입 공용 폼. 토큰은 Server Action 이 httpOnly 쿠키로만 심는다 (브라우저 JS 는 보지 못한다)
export function AuthForm({ mode, action, next }: Props) {
  const [state, formAction, pending] = useActionState(action, initialState);
  const id = useId();
  const errorId = `${id}-error`;
  const invalid = state.error ? true : undefined;
  const describedBy = state.error ? errorId : undefined;
  const signup = mode === "signup";

  return (
    <form action={formAction} className="flex flex-col gap-4">
      {next && <input type="hidden" name="next" value={next} />}
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-loginId`}>아이디</Label>
        <Input
          id={`${id}-loginId`}
          name="loginId"
          autoComplete="username"
          maxLength={30}
          required
          defaultValue={state.loginId}
          aria-invalid={invalid}
          aria-describedby={describedBy}
        />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-password`}>비밀번호</Label>
        <Input
          id={`${id}-password`}
          name="password"
          type="password"
          autoComplete={signup ? "new-password" : "current-password"}
          maxLength={64}
          required
          aria-invalid={invalid}
          aria-describedby={describedBy}
        />
      </div>
      {signup && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-passwordConfirm`}>비밀번호 확인</Label>
          <Input
            id={`${id}-passwordConfirm`}
            name="passwordConfirm"
            type="password"
            autoComplete="new-password"
            maxLength={64}
            required
          />
          <p className="text-xs text-muted-foreground">
            아이디는 영문·숫자·밑줄 4~30자, 비밀번호는 8~64자. 가입하면 1,000P 를 드립니다.
          </p>
        </div>
      )}
      <Button type="submit" disabled={pending}>
        {pending ? "처리 중..." : signup ? "가입하기" : "로그인"}
      </Button>
      {state.error && (
        <p id={errorId} role="alert" className="text-sm text-destructive">
          {state.error}
        </p>
      )}
      <p className="text-sm text-muted-foreground">
        {signup ? (
          <>
            이미 계정이 있나요?{" "}
            <Link href="/login" className="underline">
              로그인
            </Link>
          </>
        ) : (
          <>
            처음인가요?{" "}
            <Link href="/signup" className="underline">
              회원가입
            </Link>
          </>
        )}
      </p>
    </form>
  );
}
