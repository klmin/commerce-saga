import { Button } from "@commerce/ui/button";
import { useQuery } from "@tanstack/react-query";
import { type FormEvent } from "react";
import { Link } from "react-router";

import { getMe } from "@/api/me";
import { xsrfToken } from "@/lib/api";
import { basePath } from "@/lib/base";

const LINKS = [
  ["/cart", "장바구니"],
  ["/orders", "내 주문"],
  ["/points", "포인트"],
  ["/payments", "결제"],
  ["/notifications", "알림"],
] as const;

/**
 * 로그아웃: Gateway 에 폼 POST(/logout, 루트 절대 경로). Gateway 가 BFF 세션을 지우고 SAS OIDC 로그아웃(SSO 전체)을 거쳐
 * return_to(이 화면의 홈)로 돌려보낸다 (설계 문서 7-3). CSRF 토큰은 쿠키 XSRF-TOKEN 을 제출할 때 읽어 넣는다
 */
function LogoutForm() {
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const input = event.currentTarget.elements.namedItem("_csrf");
    if (input instanceof HTMLInputElement) {
      input.value = xsrfToken() ?? "";
    }
  }
  return (
    <form method="post" action="/logout" onSubmit={onSubmit}>
      <input type="hidden" name="_csrf" defaultValue="" />
      <input type="hidden" name="return_to" value={basePath()} />
      <Button type="submit" variant="outline" size="sm">
        로그아웃
      </Button>
    </form>
  );
}

export function SiteHeader() {
  const me = useQuery({ queryKey: ["me"], queryFn: getMe, staleTime: 5 * 60_000 });
  return (
    <header className="border-b">
      <div className="mx-auto flex w-full max-w-3xl items-center justify-between px-4 py-3">
        <Link to="/" className="font-semibold">
          commerce-saga
        </Link>
        {me.data && (
          <nav className="flex flex-wrap items-center justify-end gap-x-1 gap-y-2">
            {LINKS.map(([to, label]) => (
              <Button key={to} variant="ghost" size="sm" asChild>
                <Link to={to}>{label}</Link>
              </Button>
            ))}
            {/* 관리자 화면은 5-5 에서 따로(관리자 SPA). 로그인 기기 관리는 5-6 */}
            <span className="px-2 text-sm text-muted-foreground">사용자 #{me.data.userId}</span>
            <LogoutForm />
          </nav>
        )}
      </div>
    </header>
  );
}
