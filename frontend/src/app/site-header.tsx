import Link from "next/link";

import { Button } from "@/components/ui/button";
import { currentUserId } from "@/lib/auth/server-session";

import { logoutAction } from "./auth-actions";

// 쿠키를 읽으므로 요청 시점에 그린다. layout 에서 Suspense 안에 둔다 (Cache Components)
export async function SiteHeaderSession() {
  const userId = await currentUserId();
  if (!userId) {
    return (
      <nav className="flex items-center gap-2">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/login">로그인</Link>
        </Button>
        <Button size="sm" asChild>
          <Link href="/signup">회원가입</Link>
        </Button>
      </nav>
    );
  }
  return (
    <nav className="flex items-center gap-3">
      <Button variant="ghost" size="sm" asChild>
        <Link href="/orders">내 주문</Link>
      </Button>
      <span className="text-sm text-muted-foreground">사용자 #{userId}</span>
      <form action={logoutAction}>
        <Button type="submit" variant="outline" size="sm">
          로그아웃
        </Button>
      </form>
    </nav>
  );
}
