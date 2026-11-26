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
    <>
      <SiteHeader />
      <SessionGate>
        <Outlet />
      </SessionGate>
    </>
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
