import { ApiError } from "@commerce/bff/api";
import { MAX_QUANTITY } from "./orders/order-input";

/** 장바구니 화면의 ?error= 코드 → 문구 */
export const CART_ERRORS: Record<string, string> = {
  unavailable: "판매 중지됐거나 없는 상품이라 담을 수 없습니다.",
  down: "상품 정보를 확인할 수 없어 지금은 담을 수 없습니다. 잠시 뒤 다시 시도해 주세요.",
  quantity: `수량은 0~${MAX_QUANTITY} 사이 정수로 입력해 주세요. 0 이면 뺍니다.`,
  failed: "장바구니를 바꾸지 못했습니다. 다시 시도해 주세요.",
};

/** 장바구니 변경 실패 → ?error= 코드 (판매 중지·없는 상품 422, product-service 장애 503) */
export function cartErrorCode(error: unknown): string {
  if (error instanceof ApiError) {
    return error.status === 422 ? "unavailable" : error.status === 503 ? "down" : "failed";
  }
  console.error("cart change failed", error);
  return "failed";
}
