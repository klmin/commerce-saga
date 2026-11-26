import { Button } from "@commerce/ui/button";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";

import { myOrders, ORDERS_PAGE_SIZE } from "@/api/orders";
import { productNames } from "@/api/products";
import { CursorNav } from "@/components/cursor-nav";
import { OrderStatusBadge } from "@/components/order-status-badge";
import { LoadError, Page, Section } from "@/components/page";
import { ListSkeleton } from "@/components/skeletons";
import { uuidCursor } from "@/lib/history/cursor";
import { formatTime, orderTitle } from "@/lib/orders/format";
import { won } from "@/lib/products";

function OrderList() {
  const [params] = useSearchParams();
  const before = uuidCursor(params.get("before") ?? undefined);
  const orders = useQuery({ queryKey: ["orders", before], queryFn: () => myOrders(before) });
  const firstProducts = (orders.data ?? []).map((o) => o.lines[0]?.productId ?? 0);
  const names = useQuery({
    queryKey: ["product-names", firstProducts],
    queryFn: () => productNames(firstProducts),
    enabled: orders.isSuccess,
  });

  if (orders.isPending) {
    return <ListSkeleton />;
  }
  if (orders.isError) {
    return <LoadError error={orders.error} retry={() => orders.refetch()} />;
  }
  if (orders.data.length === 0) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-sm text-muted-foreground">
          {before ? "더 이전 주문이 없습니다." : "아직 주문이 없습니다."}
        </p>
        <Button size="sm" asChild>
          <Link to="/">상품 보러 가기</Link>
        </Button>
      </div>
    );
  }
  const last = orders.data.at(-1);
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col divide-y">
        {orders.data.map((o) => (
          <li key={o.orderId}>
            <Link
              to={`/orders/${o.orderId}`}
              className="flex items-center gap-4 py-3 hover:bg-muted/50"
            >
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{orderTitle(o, names.data)}</span>
                <span className="text-sm text-muted-foreground">
                  {formatTime(o.createdAt)} · {won(o.totalAmount)}
                  {o.pointAmount > 0 && ` (포인트 ${o.pointAmount.toLocaleString("ko-KR")}P)`}
                </span>
              </div>
              <OrderStatusBadge status={o.status} />
            </Link>
          </li>
        ))}
      </ul>
      <CursorNav
        basePath="/orders"
        isFirstPage={!before}
        nextCursor={orders.data.length === ORDERS_PAGE_SIZE ? last?.orderId : undefined}
        moreLabel="이전 주문 더 보기"
      />
    </div>
  );
}

export function OrdersPage() {
  return (
    <Page>
      <Section
        title="내 주문"
        action={
          <Button size="sm" asChild>
            <Link to="/orders/new">주문하기</Link>
          </Button>
        }
      >
        <OrderList />
      </Section>
    </Page>
  );
}
