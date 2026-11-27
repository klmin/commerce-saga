// 관리자 재고 조정 폼 → inventory-service /admin/stocks/{id}/adjustments 본문 (ADR 0019).
// 범위 검사는 백엔드도 한다(AdjustStockCommand). 여기서는 잘못된 값을 보내기 전에 화면에서 알려 준다

/** inventory-service AdjustStockCommand.MAX_DELTA·MAX_REASON_LENGTH */
export const MAX_DELTA = 1_000_000;
export const MAX_REASON_LENGTH = 200;

type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

export type StockAdjustInput = { delta: number; reason: string | null };

/** 수량: 입고는 양수(+ 생략 가능), 차감은 음수. 천 단위 쉼표 허용 */
export function parseStockAdjustInput(formData: FormData): Parsed<StockAdjustInput> {
  const raw = String(formData.get("delta") ?? "")
    .trim()
    .replaceAll(",", "");
  const delta = Number(raw);
  if (!/^[+-]?\d{1,7}$/.test(raw) || delta === 0 || Math.abs(delta) > MAX_DELTA) {
    return {
      ok: false,
      error: `조정 수량은 0 이 아닌 정수로, 입고는 양수·차감은 음수로 ${MAX_DELTA.toLocaleString("ko-KR")}개까지 입력하세요.`,
    };
  }
  const reason = String(formData.get("reason") ?? "").trim();
  if (reason.length > MAX_REASON_LENGTH) {
    return { ok: false, error: `사유는 ${MAX_REASON_LENGTH}자까지입니다.` };
  }
  return { ok: true, value: { delta, reason: reason || null } };
}

/** 저장 문구: "재고를 10개 늘렸습니다." / "재고를 3개 줄였습니다." */
export function adjustedMessage(delta: number, available: number): string {
  const amount = Math.abs(delta).toLocaleString("ko-KR");
  const verb = delta > 0 ? "늘렸습니다" : "줄였습니다";
  return `재고를 ${amount}개 ${verb}. 지금 주문 가능 ${available.toLocaleString("ko-KR")}개.`;
}

/** 이력 한 줄의 수량 표시: +10 / -3 */
export function signedDelta(delta: number): string {
  return `${delta > 0 ? "+" : ""}${delta.toLocaleString("ko-KR")}`;
}
