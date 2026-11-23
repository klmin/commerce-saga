import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ApiError } from "@/lib/api/client";
import { getOrder, getOrderSaga } from "@/lib/api/orders";
import { formatTime, orderTitle } from "@/lib/orders/format";
import { isFinished, sagaStateLabel, sagaSteps } from "@/lib/orders/saga-view";
import { productName, won } from "@/lib/products";

import { OrderStatusBadge } from "../order-status-badge";
import { AutoRefresh } from "./auto-refresh";
import { SagaStepList, SagaTimeline } from "./saga-progress";

export const metadata: Metadata = { title: "주문 상세 · commerce-saga" };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function load(orderId: string) {
  try {
    return await Promise.all([getOrder(orderId), getOrderSaga(orderId)]);
  } catch (e) {
    // 없는 주문과 남의 주문은 백엔드가 똑같이 404 로 답한다 (소유권 검사, ADR 0008)
    if (e instanceof ApiError && e.status === 404) {
      notFound();
    }
    throw e;
  }
}

async function OrderDetail({ params }: Pick<PageProps<"/orders/[orderId]">, "params">) {
  const { orderId } = await params;
  if (!UUID.test(orderId)) {
    notFound();
  }
  const [order, saga] = await load(orderId);
  // 주문과 Saga 는 따로 읽어 같은 순간이 아닐 수 있다(그 사이 Saga 가 끝나면 주문은 아직 PENDING).
  // 둘 다 끝난 상태일 때만 갱신을 멈춘다
  const finished = isFinished(saga.state) && order.status !== "PENDING";
  const steps = sagaSteps(saga, order);

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div className="flex flex-col gap-1">
            <CardTitle>{orderTitle(order)}</CardTitle>
            <span className="font-mono text-xs text-muted-foreground">{order.orderId}</span>
          </div>
          <OrderStatusBadge status={order.status} />
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <ul className="flex flex-col gap-1 text-sm">
            {order.lines.map((l) => (
              <li key={l.productId} className="flex justify-between">
                <span>
                  {productName(l.productId)} × {l.quantity}
                </span>
                <span>{won(l.unitPrice * l.quantity)}</span>
              </li>
            ))}
          </ul>
          <dl className="grid grid-cols-2 gap-1 border-t pt-3 text-sm">
            <dt className="text-muted-foreground">주문 금액</dt>
            <dd className="text-right">{won(order.totalAmount)}</dd>
            <dt className="text-muted-foreground">포인트</dt>
            <dd className="text-right">-{order.pointAmount.toLocaleString("ko-KR")}P</dd>
            <dt className="font-medium">결제 금액</dt>
            <dd className="text-right font-medium">{won(order.paidAmount)}</dd>
            <dt className="text-muted-foreground">주문 시각</dt>
            <dd className="text-right">{formatTime(order.createdAt)}</dd>
          </dl>
          {order.cancelReason && (
            <p role="alert" className="text-sm text-destructive">
              취소 사유: {order.cancelReason}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-4">
          <CardTitle>
            주문 처리 (Saga) · <span data-testid="saga-state">{sagaStateLabel(saga.state)}</span>
          </CardTitle>
          <AutoRefresh active={!finished} />
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <SagaStepList steps={steps} />
          {saga.failureReason && (
            <p className="text-sm">
              <span className="font-medium">실패 원인:</span> {saga.failureReason}
              {saga.compensating && " · 이미 한 단계를 역순으로 되돌리는 중입니다"}
            </p>
          )}
          <SagaTimeline saga={saga} />
        </CardContent>
      </Card>
    </>
  );
}

export default function OrderDetailPage({ params }: PageProps<"/orders/[orderId]">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <nav className="flex gap-2">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/orders">← 내 주문</Link>
        </Button>
      </nav>
      <Suspense
        fallback={
          <p role="status" className="text-sm text-muted-foreground">
            불러오는 중...
          </p>
        }
      >
        <OrderDetail params={params} />
      </Suspense>
    </main>
  );
}
