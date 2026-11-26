import { z } from "zod";

import { api } from "@commerce/bff/api";

const item = z.object({
  productId: z.number().int(),
  name: z.string().nullable(),
  quantity: z.number().int(),
  /** 담을 때 본 가격 */
  addedPrice: z.number().int(),
  /** 지금 가격 (product-service 를 못 읽었으면 null) */
  currentPrice: z.number().int().nullable(),
  /** 판매 중인지 (모르면 null) */
  onSale: z.boolean().nullable(),
  priceChanged: z.boolean(),
});

const cart = z.object({
  items: z.array(item),
  /** false 면 지금 가격을 못 읽어 담을 때 가격만 있다 */
  pricesAvailable: z.boolean(),
});

export type Cart = z.infer<typeof cart>;
export type CartItem = z.infer<typeof item>;

/** 내 장바구니 (order-service, ADR 0016) */
export async function getCart(): Promise<Cart> {
  return cart.parse(await api<unknown>("/api/cart"));
}

/** 담기·수량 바꾸기 (그 수량으로). 판매 중지·없는 상품 ApiError(422), product 장애 503 */
export async function putCartItem(productId: number, quantity: number): Promise<Cart> {
  return cart.parse(
    await api<unknown>(`/api/cart/items/${productId}`, {
      method: "PUT",
      body: JSON.stringify({ quantity }),
    }),
  );
}

export async function removeCartItem(productId: number): Promise<Cart> {
  return cart.parse(await api<unknown>(`/api/cart/items/${productId}`, { method: "DELETE" }));
}
