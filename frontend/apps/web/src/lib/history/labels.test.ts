import { describe, expect, it } from "vitest";

import { notificationTypeLabel, paymentStatusLabel, pointEntryView, signedPoints } from "./labels";

describe("pointEntryView", () => {
  it("가입 축하 지급은 +금액", () => {
    expect(pointEntryView({ kind: "SIGNUP_BONUS", amount: 1000, status: "GRANTED" })).toEqual({
      title: "가입 축하 포인트",
      delta: 1000,
      amount: 1000,
      status: { label: "지급", tone: "success" },
    });
  });

  it("모르는 지급 종류도 지급으로", () => {
    expect(pointEntryView({ kind: "EVENT", amount: 50, status: "GRANTED" }).title).toBe(
      "포인트 지급",
    );
  });

  it("주문 사용은 −금액, 거절·환불은 잔액 변화 0", () => {
    expect(pointEntryView({ kind: "ORDER", amount: 300, status: "USED" }).delta).toBe(-300);
    const rejected = pointEntryView({ kind: "ORDER", amount: 5000, status: "REJECTED" });
    expect(rejected.delta).toBe(0);
    expect(rejected.amount).toBe(5000);
    expect(rejected.status.label).toBe("사용 안 됨");
    const refunded = pointEntryView({ kind: "ORDER", amount: 200, status: "REFUNDED" });
    expect(refunded.delta).toBe(0);
    expect(refunded.status.label).toBe("환불됨");
  });
});

describe("labels", () => {
  it("결제 상태", () => {
    expect(paymentStatusLabel("APPROVED")).toEqual({ label: "결제 완료", tone: "success" });
    expect(paymentStatusLabel("UNKNOWN").tone).toBe("progress");
    expect(paymentStatusLabel("NEW_STATUS")).toEqual({ label: "NEW_STATUS", tone: "progress" });
  });

  it("알림 종류", () => {
    expect(notificationTypeLabel("OrderCancelled")).toEqual({ label: "주문 취소", tone: "danger" });
    expect(notificationTypeLabel("Other").label).toBe("Other");
  });

  it("부호 붙인 포인트", () => {
    expect(signedPoints(1000)).toBe("+1,000P");
    expect(signedPoints(-300)).toBe("−300P");
    expect(signedPoints(0)).toBe("0P");
  });
});
