import { Brand } from "@commerce/ui/brand";
import { Button } from "@commerce/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { Input } from "@commerce/ui/input";
import { Label } from "@commerce/ui/label";
import type { ComponentProps, ReactNode } from "react";

import { useCsrf } from "@/lib/csrf";

/**
 * auth-service 로 그대로 보내는 폼 (설계 문서 4절). fetch 가 아니라 브라우저의 폼 POST 다: auth-service 가 주는 302
 * (저장된 인가 요청 → 인가 코드 → 화면)를 브라우저가 화면 이동으로 따라가야 로그인이 이어진다.
 * action 은 앞 `/` 없는 상대 경로라 `<base href>` 기준으로 맞는다(경로 방식이면 /auth/login). CSRF 토큰을 받기 전에는 보내지 않는다
 */
export function AuthForm({
  title,
  action,
  submitLabel,
  error,
  children,
  footer,
}: {
  title: string;
  action: "login" | "signup";
  submitLabel: string;
  error: string | null;
  children: ReactNode;
  footer: ReactNode;
}) {
  const csrf = useCsrf();
  return (
    // 사용자·관리자 화면과 같은 로고·색 (후속 9-6). 배경을 살짝 깔아 카드가 떠 보이게
    <div className="flex min-h-svh flex-1 flex-col bg-muted/30">
      <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-6 px-4 py-8">
        <div className="flex justify-center">
          <Brand />
        </div>
        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle>
              <h1 className="text-xl">{title}</h1>
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            {csrf.status === "error" && (
              <div role="alert" className="flex flex-col items-start gap-2 text-sm">
                <p className="text-destructive">로그인 서버에 연결하지 못했습니다.</p>
                <button type="button" className="underline underline-offset-4" onClick={csrf.retry}>
                  다시 시도
                </button>
              </div>
            )}
            <form method="post" action={action} className="flex flex-col gap-4">
              {csrf.status === "ready" && (
                <input type="hidden" name={csrf.csrf.parameterName} value={csrf.csrf.token} />
              )}
              {children}
              <Button type="submit" disabled={csrf.status !== "ready"}>
                {submitLabel}
              </Button>
            </form>
            <div className="text-sm text-muted-foreground">{footer}</div>
          </CardContent>
        </Card>
      </main>
    </div>
  );
}

/** 라벨 + 입력 한 줄 */
export function Field({
  id,
  label,
  ...props
}: { id: string; label: string } & ComponentProps<"input">) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} required {...props} />
    </div>
  );
}
