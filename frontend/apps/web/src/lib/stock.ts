/** 재고 상태 문구 (목록 카드·상세). 몇 개 안 남았으면 알려 준다 */
export const LOW_STOCK = 5;

export type StockState =
  | { kind: "checking" }
  | { kind: "unknown" }
  | { kind: "soldOut" }
  | { kind: "low"; available: number }
  | { kind: "inStock"; available: number };

export function stockState(available: number | undefined, pending = false): StockState {
  if (pending) return { kind: "checking" };
  if (available === undefined) return { kind: "unknown" };
  if (available <= 0) return { kind: "soldOut" };
  if (available <= LOW_STOCK) return { kind: "low", available };
  return { kind: "inStock", available };
}

export function stockLabel(state: StockState): string {
  switch (state.kind) {
    case "checking":
      return "재고 확인 중";
    case "unknown":
      return "재고 확인 불가";
    case "soldOut":
      return "품절";
    case "low":
      return `${state.available}개 남음`;
    case "inStock":
      return `재고 ${state.available}개`;
  }
}
