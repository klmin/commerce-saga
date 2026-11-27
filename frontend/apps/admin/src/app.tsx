import { routerBasename } from "@commerce/bff/base";
import { createQueryClient, LogoutForm, SessionGate, useMe } from "@commerce/bff/session";
import { shortId } from "@commerce/bff/short-id";
import { Button } from "@commerce/ui/button";
import { Page } from "@commerce/ui/page";
import { createBrowserRouter, Link, Outlet } from "react-router";

import { HomePage } from "@/pages/home-page";
import { ErrorPage, NotFoundPage } from "@/pages/not-found-page";
import { ProductPage } from "@/pages/product-page";
import { ProductsPage } from "@/pages/products-page";

export const queryClient = createQueryClient();

function SiteHeader() {
  const me = useMe();
  return (
    <header className="border-b">
      <div className="mx-auto flex w-full max-w-3xl items-center justify-between px-4 py-3">
        <Link to="/" className="font-semibold">
          commerce-saga 관리자
        </Link>
        {me.data && (
          <nav className="flex flex-wrap items-center justify-end gap-x-1 gap-y-2">
            <Button variant="ghost" size="sm" asChild>
              <Link to="/products">상품 관리</Link>
            </Button>
            <span className="px-2 text-sm text-muted-foreground" title={me.data.userId}>
              관리자 #{shortId(me.data.userId)}
            </span>
            <LogoutForm />
          </nav>
        )}
      </div>
    </header>
  );
}

/** 관리자가 아니면 화면이 없다. 권한 검사는 Gateway(/api/admin/** = ADMIN)·서비스가 토큰으로 한다 */
function NotAdmin() {
  return (
    <Page>
      <h1 className="text-xl font-semibold">관리자만 볼 수 있는 화면입니다</h1>
      <p className="text-sm text-muted-foreground">
        관리자 역할이 있는 계정으로 다시 로그인하세요.
      </p>
      <div className="self-start">
        <LogoutForm />
      </div>
    </Page>
  );
}

/** 모든 화면은 ADMIN 역할로 로그인해야 한다(지금 사용자를 /api/me 로 확인, 없으면 로그인 시작) */
function Layout() {
  return (
    <>
      <SiteHeader />
      <SessionGate allow={(me) => me.roles.includes("ADMIN")} denied={<NotAdmin />}>
        <Outlet />
      </SessionGate>
    </>
  );
}

// 화면 경로는 Gateway·SAS 경로(/api, /oauth2, /login, /logout 등, 설계 문서 7-1)를 쓰지 않는다.
// 앞 경로(경로 방식의 /admin/)는 <base href> 로 받는다: 앱 안의 경로는 늘 루트 기준
export const router = createBrowserRouter(
  [
    {
      element: <Layout />,
      errorElement: <ErrorPage />,
      children: [
        { index: true, element: <HomePage /> },
        { path: "products", element: <ProductsPage /> },
        { path: "products/:productId", element: <ProductPage /> },
        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ],
  { basename: routerBasename() },
);
