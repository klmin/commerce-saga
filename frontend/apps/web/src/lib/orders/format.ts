import { productName } from "@/lib/products";

export function orderTitle(
  order: { lines: { productId: number }[] },
  names?: Readonly<Record<number, string>>,
): string {
  const [first, ...rest] = order.lines;
  if (!first) {
    return "주문";
  }
  const name = productName(first.productId, names);
  return rest.length > 0 ? `${name} 외 ${rest.length}건` : name;
}

/** 서버(UTC)와 브라우저가 같은 글자를 그리게 시간대를 정해 둔다 */
export function formatTime(iso: string): string {
  return new Date(iso).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", hour12: false });
}

/** Saga 단계 사이 간격(수십 ms)이 보이게 밀리초까지 */
export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString("ko-KR", {
    timeZone: "Asia/Seoul",
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    fractionalSecondDigits: 3,
  });
}
