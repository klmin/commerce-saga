import { describe, expect, it } from "vitest";

import { canCancel, historyKind, orderBadge, sagaSteps } from "./saga-view";

const h = (...events: string[]) => events.map((event) => ({ event }));
const statuses = (steps: ReturnType<typeof sagaSteps>) =>
  Object.fromEntries(steps.map((s) => [s.key, s.note ? `${s.status}:${s.note}` : s.status]));

const withPointAndPay = { pointAmount: 300, paidAmount: 38_700 };

describe("sagaSteps", () => {
  it("정상 완료: 모든 단계 done", () => {
    const steps = sagaSteps(
      {
        state: "COMPLETED",
        history: h("STARTED", "STOCK_RESERVED", "POINT_USED", "PAYMENT_APPROVED"),
      },
      withPointAndPay,
    );
    expect(statuses(steps)).toEqual({
      stock: "done",
      point: "done",
      payment: "done",
      done: "done",
    });
  });

  it("진행 중: 지금 기다리는 단계가 current, 뒤는 pending", () => {
    const steps = sagaSteps(
      { state: "USING_POINT", history: h("STARTED", "STOCK_RESERVED") },
      withPointAndPay,
    );
    expect(statuses(steps)).toEqual({
      stock: "done",
      point: "current",
      payment: "pending",
      done: "pending",
    });
  });

  it("포인트를 안 쓰면 포인트 단계는 건너뛴다", () => {
    const steps = sagaSteps(
      { state: "PAYING", history: h("STARTED", "STOCK_RESERVED") },
      { pointAmount: 0, paidAmount: 10_000 },
    );
    expect(statuses(steps)).toMatchObject({ point: "skipped", payment: "current" });
  });

  it("품절: 재고 예약 실패 → 되돌릴 것 없이 취소", () => {
    const steps = sagaSteps(
      { state: "CANCELLED", history: h("STARTED", "STOCK_RESERVATION_FAILED") },
      withPointAndPay,
    );
    expect(statuses(steps)).toEqual({
      stock: "failed",
      point: "skipped:진행 안 함",
      payment: "skipped:진행 안 함",
      done: "failed",
    });
  });

  it("결제 실패: 포인트 환불 → 재고 해제 역순으로 되돌림", () => {
    const steps = sagaSteps(
      {
        state: "CANCELLED",
        history: h(
          "STARTED",
          "STOCK_RESERVED",
          "POINT_USED",
          "PAYMENT_FAILED",
          "POINT_REFUNDED",
          "STOCK_RELEASED",
        ),
      },
      withPointAndPay,
    );
    expect(statuses(steps)).toEqual({
      stock: "undone:재고 해제",
      point: "undone:포인트 환불",
      payment: "failed",
      done: "failed",
    });
  });

  it("되돌리는 중이면 그 단계에 표시", () => {
    const steps = sagaSteps(
      {
        state: "REFUNDING_POINT",
        history: h("STARTED", "STOCK_RESERVED", "POINT_USED", "PAYMENT_FAILED"),
      },
      withPointAndPay,
    );
    expect(statuses(steps)).toMatchObject({
      stock: "done",
      point: "current:포인트 환불 중",
      payment: "failed",
      done: "pending",
    });
  });

  it("결제 응답이 끝내 없으면 결제 취소부터 되돌린다", () => {
    const steps = sagaSteps(
      {
        state: "CANCELLED",
        history: h(
          "STARTED",
          "STOCK_RESERVED",
          "TIMEOUT_RESEND",
          "TIMEOUT_GIVE_UP",
          "PAYMENT_CANCELLED",
          "STOCK_RELEASED",
        ),
      },
      { pointAmount: 0, paidAmount: 10_000 },
    );
    expect(statuses(steps)).toEqual({
      stock: "undone:재고 해제",
      point: "skipped",
      payment: "undone:결제 취소",
      done: "failed",
    });
  });
});

describe("사용자 취소 (ADR 0020)", () => {
  it("완료 후 취소: 결제 취소 → 포인트 환불 → 재고 반환, 마지막은 주문 취소", () => {
    const done = h("STARTED", "STOCK_RESERVED", "POINT_USED", "PAYMENT_APPROVED");
    const cancelling = sagaSteps(
      { state: "CANCELLING_PAYMENT", history: [...done, ...h("CANCEL_REQUESTED")] },
      withPointAndPay,
    );
    expect(statuses(cancelling)).toEqual({
      stock: "done",
      point: "done",
      payment: "current:결제 취소 중",
      done: "pending",
    });
    expect(cancelling.at(-1)?.label).toBe("주문 취소");

    const returning = sagaSteps(
      {
        state: "RETURNING_STOCK",
        history: [...done, ...h("CANCEL_REQUESTED", "PAYMENT_CANCELLED", "POINT_REFUNDED")],
      },
      withPointAndPay,
    );
    expect(statuses(returning).stock).toBe("current:재고 반환 중");

    const cancelled = sagaSteps(
      {
        state: "CANCELLED",
        history: [
          ...done,
          ...h("CANCEL_REQUESTED", "PAYMENT_CANCELLED", "POINT_REFUNDED", "STOCK_RETURNED"),
        ],
      },
      withPointAndPay,
    );
    expect(statuses(cancelled)).toEqual({
      stock: "undone:재고 반환",
      point: "undone:포인트 환불",
      payment: "undone:결제 취소",
      done: "failed",
    });
  });

  it("배지와 취소 버튼: 요청 뒤에는 취소 요청됨, 취소된 주문은 취소 불가", () => {
    expect(orderBadge({ status: "COMPLETED", cancelRequestedAt: null }).label).toBe("주문 완료");
    expect(
      orderBadge({ status: "COMPLETED", cancelRequestedAt: "2026-11-28T00:00:00Z" }).label,
    ).toBe("취소 요청됨");
    expect(
      orderBadge({ status: "CANCELLED", cancelRequestedAt: "2026-11-28T00:00:00Z" }).label,
    ).toBe("취소됨");
    expect(canCancel({ status: "PENDING" })).toBe(true);
    expect(canCancel({ status: "COMPLETED", cancelRequestedAt: null })).toBe(true);
    expect(canCancel({ status: "COMPLETED", cancelRequestedAt: "2026-11-28T00:00:00Z" })).toBe(
      false,
    );
    expect(canCancel({ status: "CANCELLED" })).toBe(false);
  });
});

describe("historyKind", () => {
  it("실패·재시도·보상·정방향을 구분한다", () => {
    expect(historyKind({ event: "STOCK_RESERVED", state: "USING_POINT" })).toBe("forward");
    expect(historyKind({ event: "PAYMENT_FAILED", state: "REFUNDING_POINT" })).toBe("failure");
    expect(historyKind({ event: "TIMEOUT_RESEND", state: "PAYING" })).toBe("retry");
    expect(historyKind({ event: "POINT_REFUNDED", state: "RELEASING_STOCK" })).toBe("compensation");
    expect(historyKind({ event: "STOCK_RELEASED", state: "CANCELLED" })).toBe("compensation");
  });
});
