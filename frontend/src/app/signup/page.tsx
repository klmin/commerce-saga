import type { Metadata } from "next";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import { signUpAction } from "../auth-actions";
import { AuthForm } from "../auth-form";

export const metadata: Metadata = { title: "회원가입 · commerce-saga" };

export default function SignUpPage() {
  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-6 px-4 py-12">
      <Card>
        <CardHeader>
          <CardTitle>회원가입</CardTitle>
        </CardHeader>
        <CardContent>
          <AuthForm mode="signup" action={signUpAction} />
        </CardContent>
      </Card>
    </main>
  );
}
