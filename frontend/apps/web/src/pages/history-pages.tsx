import { useQuery } from "@tanstack/react-query";
import { cn } from "cn";
import type { ReactNode } from "react";
import { Link, useSearchParams } from "react-router";

import {
  myNotifications,
  myPayments,
  NOTIFICATIONS_PAGE_SIZE,
  PAYMENTS_PAGE_SIZE,
} from "@/api/history";
import { myPointHistory, POINT_HISTORY_PAGE_SIZE } from "@/api/points";
import { CursorNav } from "@/components/cursor-nav";
import { LoadError, Page, Section } from "@commerce/ui/page";
import { ListSkeleton } from "@commerce/ui/skeletons";
import { ToneBadge } from "@/components/tone-badge";
import { uuidCursor } from "@/lib/history/cursor";
import {
  notificationTypeLabel,
  paymentStatusLabel,
  pointEntryView,
  signedPoints,
} from "@/lib/history/labels";
import { formatTime } from "@/lib/orders/format";
import { won } from "@/lib/products";

import { PointBalance } from "./home-page";

/** 커서 목록 공통: 불러오는 중·오류·빈 목록 */
function CursorList<T>({
  query,
  before,
  empty,
  children,
}: {
  query: {
    isPending: boolean;
    isError: boolean;
    error: unknown;
    data?: T[];
    refetch: () => unknown;
  };
  before: unknown;
  empty: [first: string, more: string];
  children: (items: T[]) => ReactNode;
}) {
  if (query.isPending) {
    return <ListSkeleton />;
  }
  if (query.isError || !query.data) {
    return <LoadError error={query.error} retry={() => query.refetch()} />;
  }
  if (query.data.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">{before !== undefined ? empty[1] : empty[0]}</p>
    );
  }
  return <div className="flex flex-col gap-4">{children(query.data)}</div>;
}

export function PointsPage() {
  const [params] = useSearchParams();
  const before = uuidCursor(params.get("before") ?? undefined);
  const history = useQuery({
    queryKey: ["points", "history", before],
    queryFn: () => myPointHistory(before),
  });
  return (
    <Page>
      <Section title="내 포인트">
        <PointBalance />
      </Section>
      <Section title="포인트 내역">
        <p className="text-sm text-muted-foreground">
          주문이 취소되면 쓴 포인트는 보상 단계에서 돌려받습니다(환불됨). 잔액이 모자라 쓰지 못한
          주문은 &quot;사용 안 됨&quot;으로 남습니다.
        </p>
        <CursorList
          query={history}
          before={before}
          empty={["아직 포인트 내역이 없습니다.", "더 이전 내역이 없습니다."]}
        >
          {(entries) => (
            <>
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
                          to={`/orders/${e.orderId}`}
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
                nextCursor={
                  entries.length === POINT_HISTORY_PAGE_SIZE ? entries.at(-1)?.entryId : undefined
                }
                moreLabel="이전 내역 더 보기"
              />
            </>
          )}
        </CursorList>
      </Section>
    </Page>
  );
}

export function PaymentsPage() {
  const [params] = useSearchParams();
  const before = uuidCursor(params.get("before") ?? undefined);
  const payments = useQuery({
    queryKey: ["payments", before],
    queryFn: () => myPayments(before),
  });
  return (
    <Page>
      <Section title="결제 내역">
        <p className="text-sm text-muted-foreground">
          결제 단계까지 간 주문만 남습니다. 승인 뒤 주문이 취소되면 PG 환불까지 끝나
          &quot;환불됨&quot;이 되고, 응답이 늦은 결제는 PG 조회로 확정될 때까지 &quot;결과 확인
          중&quot;입니다.
        </p>
        <CursorList
          query={payments}
          before={before}
          empty={["아직 결제가 없습니다.", "더 이전 결제가 없습니다."]}
        >
          {(items) => (
            <>
              <ul className="flex flex-col divide-y">
                {items.map((p) => (
                  <li key={p.paymentId}>
                    <Link
                      to={`/orders/${p.orderId}`}
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
                nextCursor={
                  items.length === PAYMENTS_PAGE_SIZE ? items.at(-1)?.paymentId : undefined
                }
                moreLabel="이전 결제 더 보기"
              />
            </>
          )}
        </CursorList>
      </Section>
    </Page>
  );
}

export function NotificationsPage() {
  const [params] = useSearchParams();
  const before = uuidCursor(params.get("before") ?? undefined);
  const notifications = useQuery({
    queryKey: ["notifications", before],
    queryFn: () => myNotifications(before),
  });
  return (
    <Page>
      <Section title="알림">
        <p className="text-sm text-muted-foreground">
          주문 Saga 가 끝나면 notification-service 가 order.events 를 받아 남깁니다(코레오그래피).
          주문 직후에는 몇 초 늦게 보일 수 있습니다.
        </p>
        <CursorList
          query={notifications}
          before={before}
          empty={["아직 알림이 없습니다.", "더 이전 알림이 없습니다."]}
        >
          {(items) => (
            <>
              <ul className="flex flex-col divide-y">
                {items.map((n) => (
                  <li key={n.id}>
                    <Link
                      to={`/orders/${n.orderId}`}
                      className="flex items-center gap-4 py-3 hover:bg-muted/50"
                    >
                      <div className="flex flex-1 flex-col">
                        <span>{n.message}</span>
                        <span className="text-sm text-muted-foreground">
                          {formatTime(n.createdAt)}
                        </span>
                      </div>
                      <ToneBadge {...notificationTypeLabel(n.type)} />
                    </Link>
                  </li>
                ))}
              </ul>
              <CursorNav
                basePath="/notifications"
                isFirstPage={!before}
                nextCursor={items.length === NOTIFICATIONS_PAGE_SIZE ? items.at(-1)?.id : undefined}
                moreLabel="이전 알림 더 보기"
              />
            </>
          )}
        </CursorList>
      </Section>
    </Page>
  );
}
