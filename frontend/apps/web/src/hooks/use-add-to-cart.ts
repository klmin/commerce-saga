import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";

import { getCart, putCartItem } from "@/api/cart";
import { cartErrorCode } from "@/lib/cart-errors";
import { MAX_QUANTITY } from "@/lib/orders/order-input";
import type { ProductId } from "@/lib/products";

/**
 * 장바구니 담기 (목록 카드·상세). 이미 있으면 그만큼 더한다(최대 MAX_QUANTITY). 결과는 장바구니 화면에서 본다.
 * 실패하면 이유를 장바구니 화면의 ?error= 로
 */
export function useAddToCart() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState<ProductId>();

  async function add(productId: ProductId, quantity = 1) {
    setAdding(productId);
    try {
      const current = (await getCart()).items.find((i) => i.productId === productId)?.quantity ?? 0;
      // 받은 장바구니로 캐시를 바꾼 뒤 옮긴다. 옛 캐시로 장바구니 주문 폼이 먼저 그려지면 방금 담은 상품이 빠진 채 주문될 수 있다
      queryClient.setQueryData(
        ["cart"],
        await putCartItem(productId, Math.min(current + quantity, MAX_QUANTITY)),
      );
      navigate("/cart");
    } catch (e) {
      navigate(`/cart?error=${cartErrorCode(e)}`);
    } finally {
      setAdding(undefined);
    }
  }

  return { add, adding };
}
