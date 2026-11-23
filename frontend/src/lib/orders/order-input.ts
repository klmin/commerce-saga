import { PRODUCTS } from "@/lib/products";

// 백엔드(order-service) 규칙과 같다. 백엔드가 최종 검사하고, 여기서는 왕복 없이 바로 알려 주려고 한 번 더 본다
export const MAX_QUANTITY = 1_000;
const IDEMPOTENCY_KEY = /^[\x21-\x7e]{1,100}$/;

export type OrderFormValues = {
  /** 상품 id → 수량 (0 이면 주문 안 함) */
  quantities: Record<number, number>;
  pointAmount: number;
};

export type OrderInput = {
  lines: { productId: number; quantity: number; unitPrice: number }[];
  pointAmount: number;
};

export function orderTotal(quantities: Record<number, number>): number {
  return PRODUCTS.reduce((sum, p) => sum + p.price * (quantities[p.id] ?? 0), 0);
}

function wholeNumber(raw: unknown): number | undefined {
  const text = String(raw ?? "").trim();
  if (text === "") {
    return 0;
  }
  if (!/^\d{1,9}$/.test(text)) {
    return undefined;
  }
  return Number(text);
}

/** 폼 값 → 주문 요청. 가격은 고정 상품 목록의 값이다 (클라이언트가 보낸 가격은 쓰지 않는다) */
export function toOrderInput(raw: {
  quantities: Record<number, unknown>;
  pointAmount: unknown;
}): { input: OrderInput; values: OrderFormValues } | { error: string; values: OrderFormValues } {
  const quantities: Record<number, number> = {};
  let invalid: string | undefined;
  for (const p of PRODUCTS) {
    const q = wholeNumber(raw.quantities[p.id]);
    if (q === undefined || q > MAX_QUANTITY) {
      invalid ??= `${p.name} 수량은 0~${MAX_QUANTITY} 사이 정수로 입력해 주세요.`;
      quantities[p.id] = 0;
    } else {
      quantities[p.id] = q;
    }
  }
  const point = wholeNumber(raw.pointAmount);
  const values = { quantities, pointAmount: point ?? 0 };
  if (invalid) {
    return { error: invalid, values };
  }
  if (point === undefined) {
    return { error: "사용할 포인트는 0 이상 정수로 입력해 주세요.", values };
  }
  const lines = PRODUCTS.filter((p) => quantities[p.id] > 0).map((p) => ({
    productId: p.id,
    quantity: quantities[p.id],
    unitPrice: p.price,
  }));
  if (lines.length === 0) {
    return { error: "주문할 상품의 수량을 입력해 주세요.", values };
  }
  if (point > orderTotal(quantities)) {
    return { error: "사용할 포인트가 주문 금액보다 많습니다.", values };
  }
  return { input: { lines, pointAmount: point }, values };
}

export function isValidIdempotencyKey(key: string): boolean {
  return IDEMPOTENCY_KEY.test(key);
}
