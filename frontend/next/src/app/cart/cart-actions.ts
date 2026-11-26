"use server";

import { redirect, unstable_rethrow } from "next/navigation";

import { getCart, putCartItem, removeCartItem } from "@/lib/api/cart";
import { ApiError } from "@/lib/api/client";
import { logError } from "@/lib/log";
import { MAX_QUANTITY } from "@/lib/orders/order-input";

function productIdOf(formData: FormData): number | undefined {
  const id = Number(formData.get("productId"));
  return Number.isInteger(id) && id > 0 ? id : undefined;
}

/** 실패하면 장바구니 화면에 이유를 보여 준다 (?error=) */
async function run(work: () => Promise<unknown>): Promise<string> {
  try {
    await work();
    return "/cart";
  } catch (e) {
    unstable_rethrow(e);
    if (e instanceof ApiError) {
      return `/cart?error=${e.status === 422 ? "unavailable" : e.status === 503 ? "down" : "failed"}`;
    }
    logError("cart action failed", e);
    return "/cart?error=failed";
  }
}

/** 상품 목록의 "담기": 이미 있으면 하나 더 */
export async function addToCartAction(formData: FormData): Promise<void> {
  const productId = productIdOf(formData);
  if (!productId) {
    redirect("/cart?error=failed");
  }
  redirect(
    await run(async () => {
      const current = (await getCart()).items.find((i) => i.productId === productId)?.quantity ?? 0;
      await putCartItem(productId, Math.min(current + 1, MAX_QUANTITY));
    }),
  );
}

export async function updateCartQuantityAction(formData: FormData): Promise<void> {
  const productId = productIdOf(formData);
  const quantity = Number(formData.get("quantity"));
  if (!productId || !Number.isInteger(quantity) || quantity < 0 || quantity > MAX_QUANTITY) {
    redirect("/cart?error=quantity");
  }
  // 0 이면 뺀다
  redirect(
    await run(() =>
      quantity === 0 ? removeCartItem(productId) : putCartItem(productId, quantity),
    ),
  );
}

export async function removeFromCartAction(formData: FormData): Promise<void> {
  const productId = productIdOf(formData);
  if (!productId) {
    redirect("/cart?error=failed");
  }
  redirect(await run(() => removeCartItem(productId)));
}
