import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { myPointHistory, POINT_HISTORY_PAGE_SIZE } from "@/lib/api/points";
import { uuidCursor } from "@/lib/history/cursor";
import { pointEntryView, signedPoints } from "@/lib/history/labels";
import { formatTime } from "@/lib/orders/format";
import { cn } from "@/lib/utils";

import { ListSkeleton, ValueSkeleton } from "../loading-skeletons";
import { CursorNav } from "../cursor-nav";
import { pointBalanceOrUndefined } from "../shop-data";
import { ToneBadge } from "../tone-badge";

export const metadata: Metadata = { title: "내 포인트 · commerce-saga" };

async function Balance() {
  const balance = await pointBalanceOrUndefined();
  if (balance === undefined) {
    return (
      <p className="text-sm text-muted-foreground">
        포인트 계정을 만드는 중입니다. 잠시 뒤 새로고침해 주세요.
      </p>
    );
  }
  return <p className="text-2xl font-semibold">{balance.toLocaleString("ko-KR")}P</p>;
}

async function History({ searchParams }: Pick<PageProps<"/points">, "searchParams">) {
  const before = uuidCursor((await searchParams).before);
  const entries = await myPointHistory(before);

  if (entries.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {before ? "더 이전 내역이 없습니다." : "아직 포인트 내역이 없습니다."}
      </p>
    );
  }
  const last = entries.at(-1);
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col divide-y">
        {entries.map((e) => {
          const view = pointEntryView(e);
          const detail = (
            <>
              <div className="flex flex-1 flex-col">
                <span className="font-medium">{view.title}</span>
                <span className="text-sm text-muted-foreground">
                  {formatTime(e.createdAt)}
                  {view.delta === 0 && ` · ${view.amount.toLocaleString("ko-KR")}P`}
                  {e.reason && ` · ${e.reason}`}
                </span>
              </div>
              <span
                className={cn(
                  "w-24 text-right font-medium tabular-nums",
                  view.delta > 0 && "text-emerald-700 dark:text-emerald-400",
                  view.delta === 0 && "text-muted-foreground",
                )}
              >
                {signedPoints(view.delta)}
              </span>
              <ToneBadge {...view.status} />
            </>
          );
          return (
            <li key={e.entryId}>
              {e.orderId ? (
                <Link
                  href={`/orders/${e.orderId}`}
                  className="flex items-center gap-4 py-3 hover:bg-muted/50"
                >
                  {detail}
                </Link>
              ) : (
                <div className="flex items-center gap-4 py-3">{detail}</div>
              )}
            </li>
          );
        })}
      </ul>
      <CursorNav
        basePath="/points"
        isFirstPage={!before}
        nextCursor={entries.length === POINT_HISTORY_PAGE_SIZE ? last?.entryId : undefined}
        moreLabel="이전 내역 더 보기"
      />
    </div>
  );
}

export default function PointsPage({ searchParams }: PageProps<"/points">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>내 포인트</CardTitle>
        </CardHeader>
        <CardContent>
          <Suspense fallback={<ValueSkeleton />}>
            <Balance />
          </Suspense>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>포인트 내역</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            주문이 취소되면 쓴 포인트는 보상 단계에서 돌려받습니다(환불됨). 잔액이 모자라 쓰지 못한
            주문은 &quot;사용 안 됨&quot;으로 남습니다.
          </p>
          <Suspense fallback={<ListSkeleton />}>
            <History searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
