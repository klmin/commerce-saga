import { ApiError } from "@commerce/bff/api";

export function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}

/** 서버(UTC)와 상관없이 같은 글자가 나오게 시간대를 정해 둔다 */
export function formatTime(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", hour12: false });
}

/** 관리자 API 실패 → 화면 문구 */
export function failureMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 403) {
      return "관리자만 바꿀 수 있습니다.";
    }
    if (e.status === 404) {
      return "없는 상품입니다.";
    }
    return e.message;
  }
  console.error("admin action failed", e);
  return "서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요.";
}

/** ?page= (0 부터). 이상한 값이면 첫 쪽 */
export function pageNumber(raw: string | null): number {
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 && n < 10_000 ? n : 0;
}
