"use server";

import { randomUUID } from "node:crypto";

import { redirect, unstable_rethrow } from "next/navigation";

import { ApiError } from "@/lib/api/client";
import { placeOrder } from "@/lib/api/orders";
import { isValidIdempotencyKey, toOrderInput } from "@/lib/orders/order-input";
import { logError } from "@/lib/log";
import { PRODUCTS } from "@/lib/products";

export type OrderFormState = {
  error?: string;
  /**
   * 다음 제출에 쓸 Idempotency-Key. 응답을 못 받은 실패(시간 초과 등)는 같은 키로 다시 보내야 주문이 두 번 생기지 않는다.
   * 같은 키에 다른 내용이라고 거절(422)됐을 때만 새 키로 바꾼다
   */
  idempotencyKey: string;
};

/**
 * 주문 생성. 폼을 열 때 정한 Idempotency-Key 를 함께 보낸다: 버튼을 두 번 누르거나 응답을 못 받고 다시 눌러도 주문은 한 건 (시나리오 10).
 * 성공하면 주문 상세(Saga 진행)로 간다
 */
export async function placeOrderAction(
  _prev: OrderFormState,
  formData: FormData,
): Promise<OrderFormState> {
  const key = String(formData.get("idempotencyKey") ?? "");
  const idempotencyKey = isValidIdempotencyKey(key) ? key : randomUUID();
  const parsed = toOrderInput({
    quantities: Object.fromEntries(PRODUCTS.map((p) => [p.id, formData.get(`qty-${p.id}`)])),
    pointAmount: formData.get("pointAmount"),
  });
  if ("error" in parsed) {
    return { error: parsed.error, idempotencyKey };
  }

  let orderId: string;
  try {
    orderId = (await placeOrder(parsed.input, idempotencyKey)).orderId;
  } catch (e) {
    // 로그인이 풀려 authedRequest 가 로그인 화면으로 보내는 경우(redirect)는 그대로 넘긴다
    unstable_rethrow(e);
    if (e instanceof ApiError) {
      if (e.status === 422) {
        // 이 키로 이미 다른 내용의 주문이 있다 (앞 요청이 실제로는 접수됐을 수 있다). 새 키로 다시 받는다
        return {
          error:
            "앞서 보낸 주문과 내용이 달라 접수하지 않았습니다. 내 주문 목록을 확인한 뒤 다시 주문해 주세요.",
          idempotencyKey: randomUUID(),
        };
      }
      return { error: e.message, idempotencyKey };
    }
    logError("placeOrderAction failed", e);
    return {
      error:
        "주문 결과를 받지 못했습니다. 다시 누르면 같은 주문으로 처리되어 두 번 주문되지 않습니다.",
      idempotencyKey,
    };
  }
  redirect(`/orders/${orderId}`);
}
