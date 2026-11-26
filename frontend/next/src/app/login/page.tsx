import type { Metadata } from "next";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { safeNextPath } from "@/lib/auth/validation";

import { loginAction } from "../auth-actions";
import { AuthForm } from "../auth-form";

export const metadata: Metadata = { title: "로그인 · commerce-saga" };

// searchParams 는 요청 시점 데이터라 Suspense 안에서 await 한다 (Cache Components)
async function LoginFormWithNext({ searchParams }: Pick<PageProps<"/login">, "searchParams">) {
  const next = (await searchParams).next;
  const path = safeNextPath(typeof next === "string" ? next : undefined);
  return <AuthForm mode="login" action={loginAction} next={path === "/" ? undefined : path} />;
}

export default function LoginPage({ searchParams }: PageProps<"/login">) {
  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-6 px-4 py-12">
      <Card>
        <CardHeader>
          <CardTitle>로그인</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<AuthForm mode="login" action={loginAction} />}>
            <LoginFormWithNext searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
