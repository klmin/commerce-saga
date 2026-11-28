import { routerBasename } from "@commerce/bff/base";
import { createQueryClient, SessionGate } from "@commerce/bff/session";
import { createBrowserRouter, Outlet } from "react-router";

import { SiteHeader } from "@/components/site-header";
import { CartPage } from "@/pages/cart-page";
import { NotificationsPage, PaymentsPage, PointsPage } from "@/pages/history-pages";
import { HomePage } from "@/pages/home-page";
import { NewOrderPage } from "@/pages/new-order-page";
import { OrderDetailPage } from "@/pages/order-detail-page";
import { OrdersPage } from "@/pages/orders-page";
import { ErrorPage, NotFoundPage } from "@/pages/not-found-page";

export const queryClient = createQueryClient();

/** 모든 화면은 로그인이 필요하다(지금 사용자를 /api/me 로 확인, 없으면 로그인 시작) */
function Layout() {
  return (
    <div className="flex min-h-svh flex-col">
      <SiteHeader />
      <div className="flex flex-1 flex-col">
        <SessionGate>
          <Outlet />
        </SessionGate>
      </div>
      <footer className="border-t">
        <p className="mx-auto w-full max-w-6xl px-4 py-6 text-xs text-muted-foreground">
          commerce-saga · 주문·재고·포인트·결제를 Saga 로 맞추는 포트폴리오 데모입니다. 실제 결제는
          일어나지 않습니다.
        </p>
      </footer>
    </div>
  );
}

// 화면 경로는 Gateway·SAS 경로(/api, /oauth2, /login, /logout 등, 설계 문서 7-1)를 쓰지 않는다
export const router = createBrowserRouter(
  [
    {
      element: <Layout />,
      errorElement: <ErrorPage />,
      children: [
        { index: true, element: <HomePage /> },
        { path: "cart", element: <CartPage /> },
        { path: "orders", element: <OrdersPage /> },
        { path: "orders/new", element: <NewOrderPage /> },
        { path: "orders/:orderId", element: <OrderDetailPage /> },
        { path: "points", element: <PointsPage /> },
        { path: "payments", element: <PaymentsPage /> },
        { path: "notifications", element: <NotificationsPage /> },
        { path: "*", element: <NotFoundPage /> },
      ],
    },
  ],
  { basename: routerBasename() },
);
