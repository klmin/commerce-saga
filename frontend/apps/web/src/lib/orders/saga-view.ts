// 주문·Saga 응답을 화면 문구와 단계로 바꾼다. 순수 함수라 서버·클라이언트 어디서나 쓴다.
// Saga 상태 전이는 order-service OrderSaga 와 같다: 재고 예약 → 포인트 차감(쓸 때만) → 결제(낼 돈이 있을 때만) → 완료.
// 실패하면 이미 한 단계를 역순으로 되돌린다: (결제 취소) → 포인트 환불(차감했을 때만) → 재고 해제 → 취소 (ADR 0003)
// 사용자 취소(ADR 0020): 진행 중이면 다음 응답에서 위처럼 되돌리고, 완료 후면 결제 취소 → 포인트 환불 → 재고 반환 → 취소

type StatusTone = "progress" | "success" | "danger";

export const ORDER_STATUS_LABEL: Record<string, { label: string; tone: StatusTone }> = {
  PENDING: { label: "처리 중", tone: "progress" },
  COMPLETED: { label: "주문 완료", tone: "success" },
  CANCELLED: { label: "취소됨", tone: "danger" },
};

const CANCEL_REQUESTED_LABEL = { label: "취소 요청됨", tone: "progress" as const };

/** 주문 배지: 취소를 요청했고 아직 끝나지 않았으면 "취소 요청됨" */
export function orderBadge(order: { status: string; cancelRequestedAt?: string | null }) {
  if (order.cancelRequestedAt && order.status !== "CANCELLED") {
    return CANCEL_REQUESTED_LABEL;
  }
  return orderStatusLabel(order.status);
}

/** 사용자가 취소할 수 있는 주문 (취소되지 않았고 아직 요청하지 않음). 진행 중이든 완료됐든 기한 없이 (ADR 0020) */
export function canCancel(order: { status: string; cancelRequestedAt?: string | null }): boolean {
  return order.status !== "CANCELLED" && !order.cancelRequestedAt;
}

export const SAGA_STATE_LABEL: Record<string, string> = {
  RESERVING_STOCK: "재고 예약 중",
  USING_POINT: "포인트 차감 중",
  PAYING: "결제 중",
  COMPLETED: "완료",
  CANCELLING_PAYMENT: "결제 취소 중",
  REFUNDING_POINT: "포인트 환불 중",
  RELEASING_STOCK: "재고 해제 중",
  RETURNING_STOCK: "재고 반환 중",
  CANCELLED: "취소",
};

export const SAGA_EVENT_LABEL: Record<string, string> = {
  STARTED: "주문 접수",
  STOCK_RESERVED: "재고 예약됨",
  STOCK_RESERVATION_FAILED: "재고 예약 실패",
  STOCK_RELEASED: "재고 해제됨",
  POINT_USED: "포인트 차감됨",
  POINT_USE_FAILED: "포인트 차감 실패",
  POINT_REFUNDED: "포인트 환불됨",
  PAYMENT_APPROVED: "결제 승인",
  PAYMENT_FAILED: "결제 실패",
  PAYMENT_CANCELLED: "결제 취소됨",
  TIMEOUT_RESEND: "응답 없음, 다시 요청",
  TIMEOUT_GIVE_UP: "응답 없음, 되돌리기 시작",
  CANCEL_REQUESTED: "취소 요청",
  STOCK_RETURNED: "재고 반환됨",
};

export function orderStatusLabel(status: string) {
  return ORDER_STATUS_LABEL[status] ?? { label: status, tone: "progress" as const };
}

export function sagaEventLabel(event: string): string {
  return SAGA_EVENT_LABEL[event] ?? event;
}

export function sagaStateLabel(state: string): string {
  return SAGA_STATE_LABEL[state] ?? state;
}

const COMPENSATION_STATES = new Set([
  "CANCELLING_PAYMENT",
  "REFUNDING_POINT",
  "RELEASING_STOCK",
  "RETURNING_STOCK",
  "CANCELLED",
]);
const FAILURE_EVENTS = new Set([
  "STOCK_RESERVATION_FAILED",
  "POINT_USE_FAILED",
  "PAYMENT_FAILED",
  "TIMEOUT_GIVE_UP",
]);

export type HistoryKind = "forward" | "retry" | "failure" | "compensation";

/** 타임라인 한 줄의 성격 (색) */
export function historyKind(step: { event: string; state: string }): HistoryKind {
  if (FAILURE_EVENTS.has(step.event)) {
    return "failure";
  }
  if (step.event === "TIMEOUT_RESEND") {
    return "retry";
  }
  if (COMPENSATION_STATES.has(step.state)) {
    return "compensation";
  }
  return "forward";
}

export type StepStatus = "done" | "current" | "failed" | "skipped" | "pending" | "undone";

export type SagaStep = {
  key: string;
  label: string;
  status: StepStatus;
  /** 되돌렸으면 무엇으로 되돌렸는지 */
  note?: string;
};

type SagaLike = {
  state: string;
  history: { event: string }[];
};

type AmountsLike = { pointAmount: number; paidAmount: number };

/**
 * 정방향 단계(재고·포인트·결제)마다 지금 어디까지 왔고, 실패했으면 무엇을 되돌렸는지.
 * 기록(history)에 남은 응답으로만 판단한다. 포인트를 안 쓰거나 낼 돈이 없으면 그 단계는 건너뛴다(OrderSaga 와 같다)
 */
export function sagaSteps(saga: SagaLike, order: AmountsLike): SagaStep[] {
  const events = new Set(saga.history.map((h) => h.event));
  const state = saga.state;

  const step = (
    key: string,
    label: string,
    opts: {
      skip: boolean;
      runningState: string;
      ok: string;
      fail: string;
      undo: string;
      undoLabel: string;
      undoingState: string;
      /** 같은 단계를 다른 방법으로 되돌리는 경우 (재고: 완료 후 취소면 해제 대신 반환, ADR 0020) */
      altUndo?: { event: string; label: string; state: string };
    },
  ): SagaStep => {
    if (opts.skip) {
      return { key, label, status: "skipped" };
    }
    if (events.has(opts.undo)) {
      return { key, label, status: "undone", note: opts.undoLabel };
    }
    if (opts.altUndo && events.has(opts.altUndo.event)) {
      return { key, label, status: "undone", note: opts.altUndo.label };
    }
    if (events.has(opts.fail)) {
      return { key, label, status: "failed" };
    }
    if (state === opts.undoingState) {
      return { key, label, status: "current", note: `${opts.undoLabel} 중` };
    }
    if (opts.altUndo && state === opts.altUndo.state) {
      return { key, label, status: "current", note: `${opts.altUndo.label} 중` };
    }
    if (events.has(opts.ok)) {
      return { key, label, status: "done" };
    }
    if (state === opts.runningState) {
      return { key, label, status: "current" };
    }
    // 앞 단계에서 실패해 취소됐으면 이 단계는 오지 않는다
    if (state === "CANCELLED") {
      return { key, label, status: "skipped", note: "진행 안 함" };
    }
    return { key, label, status: "pending" };
  };

  const steps = [
    step("stock", "재고 예약", {
      skip: false,
      runningState: "RESERVING_STOCK",
      ok: "STOCK_RESERVED",
      fail: "STOCK_RESERVATION_FAILED",
      undo: "STOCK_RELEASED",
      undoLabel: "재고 해제",
      undoingState: "RELEASING_STOCK",
      altUndo: { event: "STOCK_RETURNED", label: "재고 반환", state: "RETURNING_STOCK" },
    }),
    step("point", "포인트 차감", {
      skip: order.pointAmount === 0,
      runningState: "USING_POINT",
      ok: "POINT_USED",
      fail: "POINT_USE_FAILED",
      undo: "POINT_REFUNDED",
      undoLabel: "포인트 환불",
      undoingState: "REFUNDING_POINT",
    }),
    step("payment", "결제", {
      skip: order.paidAmount === 0,
      runningState: "PAYING",
      ok: "PAYMENT_APPROVED",
      fail: "PAYMENT_FAILED",
      undo: "PAYMENT_CANCELLED",
      undoLabel: "결제 취소",
      undoingState: "CANCELLING_PAYMENT",
    }),
  ];

  // 결제 응답이 끝내 없으면(TIMEOUT_GIVE_UP) 실패 응답 없이 결제 취소부터 되돌린다: 결제 단계는 "결제 취소"로 보인다
  // 사용자가 취소를 요청했으면 마지막 단계는 "주문 취소"로 간다 (완료된 주문이라도)
  const last: SagaStep = {
    key: "done",
    label: state === "CANCELLED" || events.has("CANCEL_REQUESTED") ? "주문 취소" : "주문 완료",
    status: state === "COMPLETED" ? "done" : state === "CANCELLED" ? "failed" : "pending",
  };
  return [...steps, last];
}

export function isFinished(state: string): boolean {
  return state === "COMPLETED" || state === "CANCELLED";
}
