import { Button } from "@commerce/ui/button";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";

import { myOrders, ORDERS_PAGE_SIZE } from "@/api/orders";
import { productsByIds } from "@/api/products";
import { ProductImage } from "@/components/product-image";
import { CursorNav } from "@/components/cursor-nav";
import { OrderStatusBadge } from "@/components/order-status-badge";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { ListSkeleton } from "@commerce/ui/skeletons";
import { uuidCursor } from "@/lib/history/cursor";
import { formatTime, orderTitle } from "@/lib/orders/format";
import { won } from "@/lib/products";

function OrderList() {
  const [params] = useSearchParams();
  const before = uuidCursor(params.get("before") ?? undefined);
  const orders = useQuery({ queryKey: ["orders", before], queryFn: () => myOrders(before) });
  const firstProducts = (orders.data ?? []).map((o) => o.lines[0]?.productId);
  const firsts = useQuery({
    queryKey: ["products-by-ids", firstProducts],
    queryFn: () => productsByIds(firstProducts),
    enabled: orders.isSuccess,
  });
  const names = Object.fromEntries(
    Object.values(firsts.data ?? {}).map((p) => [p.id, p.name] as const),
  );

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
      <ul aria-label="주문 목록" className="flex flex-col gap-3">
        {orders.data.map((o) => (
          <li key={o.orderId}>
            <Link
              to={`/orders/${o.orderId}`}
              className="flex items-center gap-4 rounded-lg border p-3 transition-colors hover:bg-muted/50"
            >
              <ProductImage
                product={{
                  name: orderTitle(o, names),
                  imagePath: firsts.data?.[o.lines[0]?.productId ?? ""]?.imagePath,
                }}
                decorative
                className="w-14 shrink-0"
              />
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{orderTitle(o, names)}</span>
                <span className="text-sm text-muted-foreground">
                  {formatTime(o.createdAt)} · {won(o.totalAmount)}
                  {o.pointAmount > 0 && ` (포인트 ${o.pointAmount.toLocaleString("ko-KR")}P)`}
                </span>
              </div>
              <OrderStatusBadge status={o.status} cancelRequestedAt={o.cancelRequestedAt} />
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
