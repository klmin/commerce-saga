import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { myOrders, ORDERS_PAGE_SIZE } from "@/lib/api/orders";
import { formatTime, orderTitle } from "@/lib/orders/format";
import { won } from "@/lib/products";

import { OrderStatusBadge } from "./order-status-badge";

export const metadata: Metadata = { title: "내 주문 · commerce-saga" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function OrderList({ searchParams }: Pick<PageProps<"/orders">, "searchParams">) {
  const raw = (await searchParams).before;
  const before = typeof raw === "string" && UUID.test(raw) ? raw : undefined;
  const orders = await myOrders(before);

  if (orders.length === 0) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-sm text-muted-foreground">
          {before ? "더 이전 주문이 없습니다." : "아직 주문이 없습니다."}
        </p>
        <Button size="sm" asChild>
          <Link href="/">상품 보러 가기</Link>
        </Button>
      </div>
    );
  }
  const last = orders.at(-1);
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col divide-y">
        {orders.map((o) => (
          <li key={o.orderId}>
            <Link
              href={`/orders/${o.orderId}`}
              className="flex items-center gap-4 py-3 hover:bg-muted/50"
            >
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{orderTitle(o)}</span>
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
      <nav className="flex gap-2">
        {before && (
          <Button variant="outline" size="sm" asChild>
            <Link href="/orders">처음으로</Link>
          </Button>
        )}
        {orders.length === ORDERS_PAGE_SIZE && last && (
          <Button variant="outline" size="sm" asChild>
            <Link href={`/orders?before=${last.orderId}`}>이전 주문 더 보기</Link>
          </Button>
        )}
      </nav>
    </div>
  );
}

export default function OrdersPage({ searchParams }: PageProps<"/orders">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <CardTitle>내 주문</CardTitle>
          <Button size="sm" asChild>
            <Link href="/orders/new">주문하기</Link>
          </Button>
        </CardHeader>
        <CardContent>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-muted-foreground">
                불러오는 중...
              </p>
            }
          >
            <OrderList searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
