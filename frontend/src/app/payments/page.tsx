import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { myPayments, PAYMENTS_PAGE_SIZE } from "@/lib/api/payments";
import { uuidCursor } from "@/lib/history/cursor";
import { paymentStatusLabel } from "@/lib/history/labels";
import { formatTime } from "@/lib/orders/format";
import { won } from "@/lib/products";

import { ListSkeleton } from "../loading-skeletons";
import { CursorNav } from "../cursor-nav";
import { ToneBadge } from "../tone-badge";

export const metadata: Metadata = { title: "결제 내역 · commerce-saga" };

async function PaymentList({ searchParams }: Pick<PageProps<"/payments">, "searchParams">) {
  const before = uuidCursor((await searchParams).before);
  const payments = await myPayments(before);

  if (payments.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {before ? "더 이전 결제가 없습니다." : "아직 결제가 없습니다."}
      </p>
    );
  }
  const last = payments.at(-1);
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col divide-y">
        {payments.map((p) => (
          <li key={p.paymentId}>
            <Link
              href={`/orders/${p.orderId}`}
              className="flex items-center gap-4 py-3 hover:bg-muted/50"
            >
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{won(p.amount)}</span>
                <span className="text-sm text-muted-foreground">
                  {formatTime(p.createdAt)}
                  {p.updatedAt !== p.createdAt && ` · 변경 ${formatTime(p.updatedAt)}`}
                  {p.reason && ` · ${p.reason}`}
                </span>
              </div>
              <ToneBadge {...paymentStatusLabel(p.status)} />
            </Link>
          </li>
        ))}
      </ul>
      <CursorNav
        basePath="/payments"
        isFirstPage={!before}
        nextCursor={payments.length === PAYMENTS_PAGE_SIZE ? last?.paymentId : undefined}
        moreLabel="이전 결제 더 보기"
      />
    </div>
  );
}

export default function PaymentsPage({ searchParams }: PageProps<"/payments">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>결제 내역</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            결제 단계까지 간 주문만 남습니다. 승인 뒤 주문이 취소되면 PG 환불까지 끝나
            &quot;환불됨&quot;이 되고, 응답이 늦은 결제는 PG 조회로 확정될 때까지 &quot;결과 확인
            중&quot;입니다.
          </p>
          <Suspense fallback={<ListSkeleton />}>
            <PaymentList searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
