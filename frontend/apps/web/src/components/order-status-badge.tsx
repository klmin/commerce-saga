import { orderStatusLabel } from "@/lib/orders/saga-view";

import { ToneBadge } from "./tone-badge";

export function OrderStatusBadge({ status }: { status: string }) {
  return <ToneBadge {...orderStatusLabel(status)} />;
}
