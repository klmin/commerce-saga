import { orderBadge } from "@/lib/orders/saga-view";

import { ToneBadge } from "./tone-badge";

/** 주문 상태 배지. 취소를 요청했고 아직 끝나지 않았으면 "취소 요청됨" (ADR 0020) */
export function OrderStatusBadge({
  status,
  cancelRequestedAt,
}: {
  status: string;
  cancelRequestedAt?: string | null;
}) {
  return <ToneBadge {...orderBadge({ status, cancelRequestedAt })} />;
}
