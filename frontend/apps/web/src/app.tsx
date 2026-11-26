import { QueryClient, useQuery } from "@tanstack/react-query";
import { createBrowserRouter, Outlet } from "react-router";

import { getMe } from "@/api/me";
import { LoadError, Page } from "@/components/page";
import { SiteHeader } from "@/components/site-header";
import { ListSkeleton } from "@/components/skeletons";
import { ApiError } from "@/lib/api";
import { routerBasename } from "@/lib/base";
import { CartPage } from "@/pages/cart-page";
import { NotificationsPage, PaymentsPage, PointsPage } from "@/pages/history-pages";
import { HomePage } from "@/pages/home-page";
import { NewOrderPage } from "@/pages/new-order-page";
import { OrderDetailPage } from "@/pages/order-detail-page";
import { OrdersPage } from "@/pages/orders-page";
import { ErrorPage, NotFoundPage } from "@/pages/not-found-page";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 4xx 는 다시 해도 같다. 5xx·네트워크 오류만 한 번 더
      retry: (count, error) =>
        count < 1 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      refetchOnWindowFocus: false,
    },
  },
});

/**
 * 모든 화면은 로그인이 필요하다(지금 Next 화면과 같다). 처음에 /api/me 로 세션을 확인하고, 없으면(401) api() 가
 * 이 주소로 돌아오게 로그인을 시작한다
 */
function Layout() {
  const me = useQuery({ queryKey: ["me"], queryFn: getMe, staleTime: 5 * 60_000 });
  return (
    <>
      <SiteHeader />
      {me.isSuccess ? (
        <Outlet />
      ) : me.isError ? (
        <Page>
          <LoadError error={me.error} retry={() => me.refetch()} />
        </Page>
      ) : (
        <Page>
          <ListSkeleton />
        </Page>
      )}
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
