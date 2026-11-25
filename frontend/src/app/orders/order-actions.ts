"use server";

import { randomUUID } from "node:crypto";

import { redirect, unstable_rethrow } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import { placeOrder } from "@/lib/api/orders";
import { logError } from "@/lib/log";
import { isValidIdempotencyKey, type ShownProduct, toOrderInput } from "@/lib/orders/order-input";

export type OrderFormState = {
  error?: string;
  /**
   * 다음 제출에 쓸 Idempotency-Key. 응답을 못 받은 실패(시간 초과 등)는 같은 키로 다시 보내야 주문이 두 번 생기지 않는다.
   * 같은 키에 다른 내용이라고 거절(422)됐을 때만 새 키로 바꾼다
   */
  idempotencyKey: string;
  /**
   * 가격이 바뀌어 거절(409)됐을 때 받은 지금 가격 (상품 id → 가격). 화면이 이 가격으로 바꿔 보여 주고,
   * 사용자가 다시 누르면 이 가격으로 주문한다 (ADR 0016). 다른 오류가 나도 유지한다
   */
  currentPrices?: Record<number, number>;
};

/**
 * 화면에 보였던 상품 (hidden 필드 productIds·name-{id}·price-{id}). 가격은 사용자가 본 값 그대로 보낸다.
 * 서버가 지금 가격과 비교하므로 여기 값을 믿지 않아도 된다
 */
function shownProducts(formData: FormData): ShownProduct[] {
  return String(formData.get("productIds") ?? "")
    .split(",")
    .map(Number)
    .filter((id) => Number.isInteger(id) && id > 0)
    .map((id) => ({
      id,
      name: String(formData.get(`name-${id}`) ?? `상품 #${id}`),
      price: Number(formData.get(`price-${id}`) ?? NaN),
    }))
    .filter((p) => Number.isInteger(p.price) && p.price >= 0);
}

function pricesOf(error: ApiError): Record<number, number> {
  const prices = error.problem?.currentPrices;
  if (!Array.isArray(prices)) {
    return {};
  }
  return Object.fromEntries(prices.map((p) => [p.productId, p.price]));
}

/**
 * 주문 생성. 폼을 열 때 정한 Idempotency-Key 를 함께 보낸다: 버튼을 두 번 누르거나 응답을 못 받고 다시 눌러도 주문은 한 건 (시나리오 10).
 * 성공하면 주문 상세(Saga 진행)로 간다
 */
export async function placeOrderAction(
  prev: OrderFormState,
  formData: FormData,
): Promise<OrderFormState> {
  const key = String(formData.get("idempotencyKey") ?? "");
  const idempotencyKey = isValidIdempotencyKey(key) ? key : randomUUID();
  const currentPrices = prev.currentPrices;
  const products = shownProducts(formData);
  const parsed = toOrderInput(
    {
      quantities: Object.fromEntries(products.map((p) => [p.id, formData.get(`qty-${p.id}`)])),
      pointAmount: formData.get("pointAmount"),
    },
    products,
  );
  if ("error" in parsed) {
    return { error: parsed.error, idempotencyKey, currentPrices };
  }

  let orderId: string;
  try {
    const fromCart = formData.get("fromCart") === "true";
    orderId = (
      await placeOrder(fromCart ? { ...parsed.input, fromCart } : parsed.input, idempotencyKey)
    ).orderId;
  } catch (e) {
    // 로그인이 풀려 authedRequest 가 로그인 화면으로 보내는 경우(redirect)는 그대로 넘긴다
    unstable_rethrow(e);
    if (e instanceof ApiError) {
      if (e.status === 409) {
        // 화면 가격과 지금 가격이 다르다. 주문은 만들어지지 않았고 키도 남지 않아 같은 키로 다시 보내면 된다
        return {
          error:
            "그사이 상품 가격이 바뀌었습니다. 바뀐 가격을 확인한 뒤 다시 주문하기를 눌러 주세요.",
          idempotencyKey,
          currentPrices: { ...currentPrices, ...pricesOf(e) },
        };
      }
      if (e.status === 422 && e.message.includes("주문할 수 없는 상품")) {
        // 판매 중지·없는 상품. 주문은 만들어지지 않았다
        return { error: e.message, idempotencyKey, currentPrices };
      }
      if (e.status === 422) {
        // 이 키로 이미 다른 내용의 주문이 있다 (앞 요청이 실제로는 접수됐을 수 있다). 새 키로 다시 받는다
        return {
          error:
            "앞서 보낸 주문과 내용이 달라 접수하지 않았습니다. 내 주문 목록을 확인한 뒤 다시 주문해 주세요.",
          idempotencyKey: randomUUID(),
          currentPrices,
        };
      }
      return { error: e.message, idempotencyKey, currentPrices };
    }
    logError("placeOrderAction failed", e);
    return {
      error:
        "주문 결과를 받지 못했습니다. 다시 누르면 같은 주문으로 처리되어 두 번 주문되지 않습니다.",
      idempotencyKey,
      currentPrices,
    };
  }
  redirect(`/orders/${orderId}`);
}
