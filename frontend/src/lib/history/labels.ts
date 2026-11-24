// 포인트·결제·알림 목록 응답을 화면 문구로 바꾼다. 순수 함수라 서버·클라이언트 어디서나 쓴다.

export type Tone = "progress" | "success" | "danger" | "muted";

export type PointEntryView = {
  title: string;
  /** 잔액에 반영된 변화. 거절·환불된 사용은 0 */
  delta: number;
  /** 원래 금액 (취소선 등으로 보여 줄 때) */
  amount: number;
  status: { label: string; tone: Tone };
};

const POINT_USAGE_STATUS: Record<string, { label: string; tone: Tone }> = {
  USED: { label: "사용", tone: "success" },
  REJECTED: { label: "사용 안 됨", tone: "danger" },
  REFUNDED: { label: "환불됨", tone: "muted" },
};

const GRANT_TITLE: Record<string, string> = {
  SIGNUP_BONUS: "가입 축하 포인트",
};

/**
 * 포인트 내역 한 줄. kind 가 ORDER 면 주문 사용(USED·REJECTED·REFUNDED), 그 밖은 지급(GRANTED)
 * - USED: 잔액 −amount
 * - REJECTED(잔액 부족 등): 차감하지 않았다 → 0
 * - REFUNDED(주문 취소로 보상): 차감했다가 돌려줬다 → 0
 */
export function pointEntryView(entry: {
  kind: string;
  amount: number;
  status: string;
}): PointEntryView {
  if (entry.kind !== "ORDER") {
    return {
      title: GRANT_TITLE[entry.kind] ?? "포인트 지급",
      delta: entry.amount,
      amount: entry.amount,
      status: { label: "지급", tone: "success" },
    };
  }
  return {
    title: "주문에 사용",
    delta: entry.status === "USED" ? -entry.amount : 0,
    amount: entry.amount,
    status: POINT_USAGE_STATUS[entry.status] ?? { label: entry.status, tone: "progress" },
  };
}

const PAYMENT_STATUS: Record<string, { label: string; tone: Tone }> = {
  APPROVED: { label: "결제 완료", tone: "success" },
  DECLINED: { label: "결제 거절", tone: "danger" },
  UNKNOWN: { label: "결과 확인 중", tone: "progress" },
  CANCELLED: { label: "결제 전 취소", tone: "muted" },
  REFUNDED: { label: "환불됨", tone: "muted" },
};

export function paymentStatusLabel(status: string): { label: string; tone: Tone } {
  return PAYMENT_STATUS[status] ?? { label: status, tone: "progress" };
}

const NOTIFICATION_TYPE: Record<string, { label: string; tone: Tone }> = {
  OrderCompleted: { label: "주문 완료", tone: "success" },
  OrderCancelled: { label: "주문 취소", tone: "danger" },
};

export function notificationTypeLabel(type: string): { label: string; tone: Tone } {
  return NOTIFICATION_TYPE[type] ?? { label: type, tone: "muted" };
}

/** 부호를 붙인 포인트 (+1,000P, −300P, 0P) */
export function signedPoints(delta: number): string {
  const abs = Math.abs(delta).toLocaleString("ko-KR");
  if (delta > 0) {
    return `+${abs}P`;
  }
  return delta < 0 ? `−${abs}P` : "0P";
}
