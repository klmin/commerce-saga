import { Button } from "@commerce/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@commerce/ui/card";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";

import { getOrder, getOrderSaga } from "@/api/orders";
import { productNames } from "@/api/products";
import { OrderStatusBadge } from "@/components/order-status-badge";
import { LoadError, Page } from "@commerce/ui/page";
import { SagaStepList, SagaTimeline } from "@/components/saga-progress";
import { OrderDetailSkeleton } from "@commerce/ui/skeletons";
import { ApiError } from "@commerce/bff/api";
import { formatTime, orderTitle } from "@/lib/orders/format";
import { isFinished, sagaStateLabel, sagaSteps } from "@/lib/orders/saga-view";
import { productName, won } from "@/lib/products";

import { NotFoundPage } from "./not-found-page";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const INTERVAL_MS = 1_000;
// 결제 타임아웃·재전송이 겹치면 Saga 가 수십 초 걸린다. 그보다 길면 손으로 다시 확인하게 멈춘다
const MAX_MS = 120_000;

/** Saga 가 끝날 때까지 1초마다 다시 읽는다. 2분이 지나면 멈추고 "다시 확인" 버튼 */
function usePolling(active: boolean) {
  const [startedAt, setStartedAt] = useState(() => Date.now());
  const [stopped, setStopped] = useState(false);
  useEffect(() => {
    if (!active || stopped) {
      return;
    }
    const timer = setTimeout(() => setStopped(true), Math.max(0, startedAt + MAX_MS - Date.now()));
    return () => clearTimeout(timer);
  }, [active, stopped, startedAt]);
  return {
    interval: active && !stopped ? INTERVAL_MS : false,
    stopped: active && stopped,
    restart: () => {
      setStartedAt(Date.now());
      setStopped(false);
    },
  } as const;
}

function OrderDetail({ orderId }: { orderId: string }) {
  const [done, setDone] = useState(false);
  const polling = usePolling(!done);
  const order = useQuery({
    queryKey: ["order", orderId],
    queryFn: () => getOrder(orderId),
    refetchInterval: polling.interval,
  });
  const saga = useQuery({
    queryKey: ["order", orderId, "saga"],
    queryFn: () => getOrderSaga(orderId),
    refetchInterval: polling.interval,
  });
  const names = useQuery({
    queryKey: ["product-names", order.data?.lines.map((l) => l.productId)],
    queryFn: () => productNames(order.data?.lines.map((l) => l.productId) ?? []),
    enabled: order.isSuccess,
    staleTime: Infinity,
  });

  // 주문과 Saga 는 따로 읽어 같은 순간이 아닐 수 있다(그 사이 Saga 가 끝나면 주문은 아직 PENDING).
  // 둘 다 끝난 상태일 때만 갱신을 멈춘다. 렌더 중에 한 번만 바꾼다(끝난 뒤에는 다시 읽지 않아 값이 그대로다)
  const finished =
    saga.data !== undefined &&
    order.data !== undefined &&
    isFinished(saga.data.state) &&
    order.data.status !== "PENDING";
  if (finished && !done) {
    setDone(true);
  }

  const error = order.error ?? saga.error;
  // 없는 주문과 남의 주문은 백엔드가 똑같이 404 로 답한다 (소유권 검사, ADR 0008)
  if (error instanceof ApiError && error.status === 404) {
    return <NotFoundPage embedded />;
  }
  if (error && !(order.data && saga.data)) {
    return (
      <LoadError
        error={error}
        retry={() => {
          void order.refetch();
          void saga.refetch();
        }}
      />
    );
  }
  if (!order.data || !saga.data) {
    return <OrderDetailSkeleton />;
  }
  const steps = sagaSteps(saga.data, order.data);

  return (
    <>
      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div className="flex flex-col gap-1">
            <CardTitle>{orderTitle(order.data, names.data)}</CardTitle>
            <span className="font-mono text-xs text-muted-foreground">{order.data.orderId}</span>
          </div>
          <OrderStatusBadge status={order.data.status} />
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <ul className="flex flex-col gap-1 text-sm">
            {order.data.lines.map((l) => (
              <li key={l.productId} className="flex justify-between">
                <span>
                  {productName(l.productId, names.data)} × {l.quantity}
                </span>
                <span>{won(l.unitPrice * l.quantity)}</span>
              </li>
            ))}
          </ul>
          <dl className="grid grid-cols-2 gap-1 border-t pt-3 text-sm">
            <dt className="text-muted-foreground">주문 금액</dt>
            <dd className="text-right">{won(order.data.totalAmount)}</dd>
            <dt className="text-muted-foreground">포인트</dt>
            <dd className="text-right">-{order.data.pointAmount.toLocaleString("ko-KR")}P</dd>
            <dt className="font-medium">결제 금액</dt>
            <dd className="text-right font-medium">{won(order.data.paidAmount)}</dd>
            <dt className="text-muted-foreground">주문 시각</dt>
            <dd className="text-right">{formatTime(order.data.createdAt)}</dd>
          </dl>
          {order.data.cancelReason && (
            <p role="alert" className="text-sm text-destructive">
              취소 사유: {order.data.cancelReason}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-4">
          <CardTitle>
            주문 처리 (Saga) ·{" "}
            <span data-testid="saga-state">{sagaStateLabel(saga.data.state)}</span>
          </CardTitle>
          {!done &&
            (polling.stopped ? (
              <Button variant="outline" size="sm" onClick={polling.restart}>
                다시 확인
              </Button>
            ) : (
              <p role="status" className="text-sm text-muted-foreground">
                진행 중... 자동으로 갱신합니다
              </p>
            ))}
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          <SagaStepList steps={steps} />
          {saga.data.failureReason && (
            <p className="text-sm">
              <span className="font-medium">실패 원인:</span> {saga.data.failureReason}
              {saga.data.compensating && " · 이미 한 단계를 역순으로 되돌리는 중입니다"}
            </p>
          )}
          <SagaTimeline saga={saga.data} />
        </CardContent>
      </Card>
    </>
  );
}

export function OrderDetailPage() {
  const { orderId = "" } = useParams();
  return (
    <Page>
      <nav className="flex gap-2">
        <Button variant="ghost" size="sm" asChild>
          <Link to="/orders">← 내 주문</Link>
        </Button>
      </nav>
      {UUID.test(orderId) ? (
        <OrderDetail key={orderId} orderId={orderId} />
      ) : (
        <NotFoundPage embedded />
      )}
    </Page>
  );
}
