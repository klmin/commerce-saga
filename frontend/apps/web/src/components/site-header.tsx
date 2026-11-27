import { LogoutForm, useMe } from "@commerce/bff/session";
import { shortId } from "@commerce/bff/short-id";
import { Button } from "@commerce/ui/button";
import { Link } from "react-router";

const LINKS = [
  ["/cart", "장바구니"],
  ["/orders", "내 주문"],
  ["/points", "포인트"],
  ["/payments", "결제"],
  ["/notifications", "알림"],
] as const;

export function SiteHeader() {
  const me = useMe();
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
            {/* 관리자 화면은 따로(관리자 SPA, 다른 호스트·경로). 로그인 기기는 인증 화면(SAS 주소, Gateway 가 알려 줌) */}
            {me.data.devicesUrl && (
              <Button variant="ghost" size="sm" asChild>
                <a href={me.data.devicesUrl}>로그인 기기</a>
              </Button>
            )}
            <span className="px-2 text-sm text-muted-foreground" title={me.data.userId}>
              사용자 #{shortId(me.data.userId)}
            </span>
            <LogoutForm />
          </nav>
        )}
      </div>
    </header>
  );
}
