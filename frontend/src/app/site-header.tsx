import Link from "next/link";

import { Button } from "@/components/ui/button";
import { currentUserId, isAdmin } from "@/lib/auth/server-session";

import { logoutAction } from "./auth-actions";
import { LinkPendingHint } from "./link-pending-hint";

// 쿠키를 읽으므로 요청 시점에 그린다. layout 에서 Suspense 안에 둔다 (Cache Components)
export async function SiteHeaderSession() {
  const [userId, admin] = await Promise.all([currentUserId(), isAdmin()]);
  if (!userId) {
    return (
      <nav className="flex items-center gap-2">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/login" className="relative">
            로그인
            <LinkPendingHint />
          </Link>
        </Button>
        <Button size="sm" asChild>
          <Link href="/signup" className="relative">
            회원가입
            <LinkPendingHint />
          </Link>
        </Button>
      </nav>
    );
  }
  return (
    <nav className="flex flex-wrap items-center justify-end gap-x-1 gap-y-2">
      <Button variant="ghost" size="sm" asChild>
        <Link href="/orders" className="relative">
          내 주문
          <LinkPendingHint />
        </Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/points" className="relative">
          포인트
          <LinkPendingHint />
        </Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/payments" className="relative">
          결제
          <LinkPendingHint />
        </Link>
      </Button>
      <Button variant="ghost" size="sm" asChild>
        <Link href="/notifications" className="relative">
          알림
          <LinkPendingHint />
        </Link>
      </Button>
      {/* 메뉴만 숨긴다. 관리자 API 권한은 Gateway 가 토큰의 roles 로 검사한다 */}
      {admin && (
        <Button variant="ghost" size="sm" asChild>
          <Link href="/admin" className="relative">
            관리자
            <LinkPendingHint />
          </Link>
        </Button>
      )}
      <Button variant="ghost" size="sm" asChild>
        <Link href="/sessions" title="로그인 기기 관리" className="relative">
          사용자 #{userId}
          <LinkPendingHint />
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
