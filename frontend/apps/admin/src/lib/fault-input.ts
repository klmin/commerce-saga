// 관리자 결제 장애 주입 폼 → payment-service PUT /admin/payment-faults 본문.
// 범위 검사는 백엔드도 한다(@PositiveOrZero @Max). 여기서는 잘못된 값을 보내기 전에 화면에서 알려 준다

export const FAULT_MODES = ["APPROVE", "DECLINE", "DELAY"] as const;
export type FaultMode = (typeof FAULT_MODES)[number];

/** payment-service Settings.MAX_DELAY_MILLIS */
export const MAX_DELAY_MILLIS = 60_000;

export const FAULT_MODE_LABEL: Record<FaultMode, string> = {
  APPROVE: "정상 승인",
  DECLINE: "거절",
  DELAY: "지연 후 승인",
};

export type FaultSettings = { mode: FaultMode; delayMillis: number };

export function parseFaultInput(
  formData: FormData,
): { ok: true; value: FaultSettings } | { ok: false; error: string } {
  const mode = String(formData.get("mode") ?? "");
  if (!(FAULT_MODES as readonly string[]).includes(mode)) {
    return { ok: false, error: "모드를 고르세요." };
  }
  if (mode !== "DELAY") {
    // 지연은 DELAY 에서만 쓴다. 다른 모드로 바꾸면 0 으로 돌려 둔다
    return { ok: true, value: { mode: mode as FaultMode, delayMillis: 0 } };
  }
  const raw = String(formData.get("delayMillis") ?? "").trim();
  if (!/^\d{1,6}$/.test(raw)) {
    return { ok: false, error: "지연 시간은 0 이상의 정수(ms)로 입력하세요." };
  }
  const delayMillis = Number(raw);
  if (delayMillis > MAX_DELAY_MILLIS) {
    return {
      ok: false,
      error: `지연 시간은 ${MAX_DELAY_MILLIS.toLocaleString("ko-KR")}ms 까지입니다.`,
    };
  }
  return { ok: true, value: { mode: "DELAY", delayMillis } };
}
