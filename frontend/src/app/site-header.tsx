import Link from "next/link";

import { Button } from "@/components/ui/button";
import { currentUserId, isAdmin } from "@/lib/auth/server-session";

import { logoutAction } from "./auth-actions";

// 쿠키를 읽으므로 요청 시점에 그린다. layout 에서 Suspense 안에 둔다 (Cache Components)
export async function SiteHeaderSession() {
  const [userId, admin] = await Promise.all([currentUserId(), isAdmin()]);
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
    <nav className="flex flex-wrap items-center justify-end gap-x-1 gap-y-2">
      <Button variant="ghost" size="sm" asChild>
        <Link href="/orders">내 주문</Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/points">포인트</Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/payments">결제</Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/notifications">알림</Link>
      </Button>
      {/* 메뉴만 숨긴다. 관리자 API 권한은 Gateway 가 토큰의 roles 로 검사한다 */}
      {admin && (
        <Button variant="ghost" size="sm" asChild>
          <Link href="/admin">관리자</Link>
        </Button>
      )}
      <Button variant="ghost" size="sm" asChild>
        <Link href="/sessions" title="로그인 기기 관리">
          사용자 #{userId}
        </Link>
      </Button>
      <form action={logoutAction}>
        <Button type="submit" variant="outline" size="sm">
          로그아웃
        </Button>
      </form>
    </nav>
  );
}
