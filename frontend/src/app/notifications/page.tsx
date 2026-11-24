import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { myNotifications, NOTIFICATIONS_PAGE_SIZE } from "@/lib/api/notifications";
import { numberCursor } from "@/lib/history/cursor";
import { notificationTypeLabel } from "@/lib/history/labels";
import { formatTime } from "@/lib/orders/format";

import { CursorNav } from "../cursor-nav";
import { ToneBadge } from "../tone-badge";

export const metadata: Metadata = { title: "알림 · commerce-saga" };

async function NotificationList({
  searchParams,
}: Pick<PageProps<"/notifications">, "searchParams">) {
  const before = numberCursor((await searchParams).before);
  const notifications = await myNotifications(before);

  if (notifications.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        {before ? "더 이전 알림이 없습니다." : "아직 알림이 없습니다."}
      </p>
    );
  }
  const last = notifications.at(-1);
  return (
    <div className="flex flex-col gap-4">
      <ul className="flex flex-col divide-y">
        {notifications.map((n) => (
          <li key={n.id}>
            <Link
              href={`/orders/${n.orderId}`}
              className="flex items-center gap-4 py-3 hover:bg-muted/50"
            >
              <div className="flex flex-1 flex-col">
                <span>{n.message}</span>
                <span className="text-sm text-muted-foreground">{formatTime(n.createdAt)}</span>
              </div>
              <ToneBadge {...notificationTypeLabel(n.type)} />
            </Link>
          </li>
        ))}
      </ul>
      <CursorNav
        basePath="/notifications"
        isFirstPage={before === undefined}
        nextCursor={
          notifications.length === NOTIFICATIONS_PAGE_SIZE && last ? String(last.id) : undefined
        }
        moreLabel="이전 알림 더 보기"
      />
    </div>
  );
}

export default function NotificationsPage({ searchParams }: PageProps<"/notifications">) {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-8">
      <Card>
        <CardHeader>
          <CardTitle>알림</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            주문 Saga 가 끝나면 notification-service 가 order.events 를 받아 남깁니다(코레오그래피).
            주문 직후에는 몇 초 늦게 보일 수 있습니다.
          </p>
          <Suspense
            fallback={
              <p role="status" className="text-sm text-muted-foreground">
                불러오는 중...
              </p>
            }
          >
            <NotificationList searchParams={searchParams} />
          </Suspense>
        </CardContent>
      </Card>
    </main>
  );
}
