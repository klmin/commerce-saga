import { orderStatusLabel } from "@/lib/orders/saga-view";
import { cn } from "@/lib/utils";

const TONE = {
  progress: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  success: "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  danger: "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-200",
} as const;

export function OrderStatusBadge({ status }: { status: string }) {
  const { label, tone } = orderStatusLabel(status);
  return (
    <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium", TONE[tone])}>{label}</span>
  );
}
