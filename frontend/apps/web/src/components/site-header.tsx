import { LogoutForm, useMe } from "@commerce/bff/session";
import { shortId } from "@commerce/bff/short-id";
import { Brand } from "@commerce/ui/brand";
import { Button } from "@commerce/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@commerce/ui/dropdown-menu";
import { Input } from "@commerce/ui/input";
import { useQuery } from "@tanstack/react-query";
import { Link, NavLink, useLocation, useNavigate, useSearchParams } from "react-router";

import { getCart } from "@/api/cart";
import { categoryUrl, headerSearchUrl } from "@/lib/search";
import { useCategories } from "@/hooks/use-categories";

/** 홈(상품 목록)에서만 지금 URL 의 검색 조건을 이어 쓴다. 다른 화면의 ?page 등은 검색 조건이 아니다 */
function useHomeParams(): URLSearchParams {
  const { pathname } = useLocation();
  const [params] = useSearchParams();
  return pathname === "/" ? params : new URLSearchParams();
}

/** 상품 검색창. 검색어는 홈 URL(?q=)로 넘기고, 고른 카테고리는 유지한다 */
function HeaderSearch() {
  const navigate = useNavigate();
  const params = useHomeParams();
  const q = params.get("q") ?? "";
  return (
    <form
      role="search"
      aria-label="상품 검색"
      className="order-last flex w-full gap-2 md:order-none md:max-w-md md:flex-1"
      onSubmit={(e) => {
        e.preventDefault();
        navigate(headerSearchUrl(String(new FormData(e.currentTarget).get("q") ?? ""), params));
      }}
    >
      <label htmlFor="header-q" className="sr-only">
        상품 검색어
      </label>
      {/* URL 의 검색어가 바뀌면(전체 보기·카테고리 이동) 입력을 다시 채운다 */}
      <Input
        key={q}
        id="header-q"
        name="q"
        type="search"
        maxLength={100}
        placeholder="상품 검색 (예: 마우)"
        defaultValue={q}
      />
      <Button type="submit" variant="secondary">
        검색
      </Button>
    </form>
  );
}

/** 장바구니 줄 수. 담기·수량 변경·주문은 ["cart"] 캐시를 바꾸거나 다시 읽게 해 여기도 따라 바뀐다 */
function CartLink() {
  const cart = useQuery({ queryKey: ["cart"], queryFn: getCart });
  const count = cart.data?.items.length ?? 0;
  return (
    <Button variant="ghost" size="sm" asChild>
      <NavLink to="/cart" aria-label={count > 0 ? `장바구니 (${count}개)` : "장바구니"}>
        장바구니
        {count > 0 && (
          <span
            aria-hidden="true"
            className="rounded-full bg-primary px-1.5 text-xs leading-5 font-semibold text-primary-foreground"
          >
            {count}
          </span>
        )}
      </NavLink>
    </Button>
  );
}

const ACCOUNT_LINKS = [
  ["/points", "포인트"],
  ["/payments", "결제 내역"],
  ["/notifications", "알림"],
] as const;

/** 자주 쓰지 않는 내 정보는 메뉴 하나로 (좁은 화면에서도 헤더가 한 줄에 들어오게) */
function AccountMenu({ devicesUrl }: { devicesUrl?: string | null }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm">
          내 정보 ▾
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>내 정보</DropdownMenuLabel>
        {ACCOUNT_LINKS.map(([to, label]) => (
          <DropdownMenuItem key={to} asChild>
            <Link to={to}>{label}</Link>
          </DropdownMenuItem>
        ))}
        {/* 로그인 기기는 인증 화면(SAS 주소, Gateway 가 알려 줌) */}
        {devicesUrl && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem asChild>
              <a href={devicesUrl}>로그인 기기</a>
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** 카테고리 메뉴 (ADR 0021). 못 읽으면 메뉴만 빠지고 화면은 그대로 */
function CategoryNav() {
  const categories = useCategories();
  const { pathname } = useLocation();
  const params = useHomeParams();
  const active = pathname === "/" ? (params.get("category") ?? "") : null;
  const items = [{ code: "", name: "전체" }, ...(categories.data ?? [])];
  return (
    <nav aria-label="카테고리" className="border-t">
      <ul className="mx-auto flex h-10 w-full max-w-6xl items-center gap-1 overflow-x-auto px-4 text-sm whitespace-nowrap">
        {categories.data &&
          items.map((c) => {
            const current = active === c.code;
            return (
              <li key={c.code || "all"}>
                <Link
                  to={categoryUrl(c.code || undefined, params)}
                  aria-current={current ? "page" : undefined}
                  className={
                    current
                      ? "rounded-full bg-primary px-3 py-1 font-medium text-primary-foreground"
                      : "rounded-full px-3 py-1 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
                  }
                >
                  {c.name}
                </Link>
              </li>
            );
          })}
      </ul>
    </nav>
  );
}

export function SiteHeader() {
  const me = useMe();
  return (
    <header className="sticky top-0 z-40 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/80">
      <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 md:flex-nowrap">
        <Link to="/">
          <Brand />
        </Link>
        <HeaderSearch />
        {me.data && (
          <nav aria-label="내 메뉴" className="ml-auto flex items-center gap-1">
            <CartLink />
            <Button variant="ghost" size="sm" asChild>
              <NavLink to="/orders" end={false}>
                내 주문
              </NavLink>
            </Button>
            <AccountMenu devicesUrl={me.data.devicesUrl} />
            <span
              className="hidden px-2 text-sm text-muted-foreground lg:inline"
              title={me.data.userId}
            >
              사용자 #{shortId(me.data.userId)}
            </span>
            <LogoutForm />
          </nav>
        )}
      </div>
      <CategoryNav />
    </header>
  );
}
