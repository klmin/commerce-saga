import type { OrderSaga } from "@/lib/api/orders";
import { formatClock } from "@/lib/orders/format";
import {
  historyKind,
  type HistoryKind,
  sagaEventLabel,
  sagaStateLabel,
  type SagaStep,
  type StepStatus,
} from "@/lib/orders/saga-view";
import { cn } from "@/lib/utils";

const STEP_STYLE: Record<StepStatus, { mark: string; className: string; text: string }> = {
  done: { mark: "✓", className: "border-emerald-500 text-emerald-700", text: "완료" },
  current: { mark: "…", className: "border-amber-500 text-amber-700", text: "진행 중" },
  failed: { mark: "✕", className: "border-red-500 text-red-700", text: "실패" },
  undone: { mark: "↺", className: "border-violet-500 text-violet-700", text: "되돌림" },
  skipped: { mark: "–", className: "border-muted text-muted-foreground", text: "건너뜀" },
  pending: { mark: "", className: "border-muted text-muted-foreground", text: "대기" },
};

/** 재고 → 포인트 → 결제 → 완료. 실패하면 어느 단계에서 멈췄고 무엇을 되돌렸는지 */
export function SagaStepList({ steps }: { steps: SagaStep[] }) {
  return (
    <ol className="grid grid-cols-2 gap-2 sm:grid-cols-4" aria-label="Saga 단계">
      {steps.map((s) => {
        const style = STEP_STYLE[s.status];
        return (
          <li
            key={s.key}
            data-status={s.status}
            className={cn("flex flex-col gap-1 rounded-md border-2 p-3", style.className)}
          >
            <span className="text-sm font-medium">
              <span aria-hidden="true">{style.mark} </span>
              {s.label}
            </span>
            <span className="text-xs">{s.note ?? style.text}</span>
          </li>
        );
      })}
    </ol>
  );
}

const KIND_STYLE: Record<HistoryKind, { dot: string; label: string }> = {
  forward: { dot: "bg-emerald-500", label: "정방향" },
  retry: { dot: "bg-amber-500", label: "재시도" },
  failure: { dot: "bg-red-500", label: "실패" },
  compensation: { dot: "bg-violet-500", label: "보상" },
};

/** order-service 가 Saga 상태를 바꿀 때마다 남긴 기록 (GET /orders/{id}/saga 의 history) */
export function SagaTimeline({ saga }: { saga: OrderSaga }) {
  return (
    <ol className="flex flex-col gap-3 border-l pl-4" aria-label="Saga 기록">
      {saga.history.map((h, i) => {
        const kind = historyKind(h);
        const style = KIND_STYLE[kind];
        return (
          <li key={`${h.occurredAt}-${i}`} data-kind={kind} className="relative flex flex-col">
            <span
              aria-hidden="true"
              className={cn("absolute top-1.5 -left-[21px] size-2.5 rounded-full", style.dot)}
            />
            <span className="text-sm">
              <span className="font-medium">{sagaEventLabel(h.event)}</span>
              <span className="text-muted-foreground"> → {sagaStateLabel(h.state)}</span>
              <span className="sr-only"> ({style.label})</span>
            </span>
            <span className="font-mono text-xs text-muted-foreground">
              {formatClock(h.occurredAt)} · {h.event}
            </span>
            {h.reason && <span className="text-xs text-muted-foreground">{h.reason}</span>}
          </li>
        );
      })}
    </ol>
  );
}
