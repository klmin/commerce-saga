import "server-only";

import { z } from "zod";

import { authedRequest } from "./authed";

const stock = z.object({
  productId: z.number().int(),
  available: z.number().int(),
  reserved: z.number().int(),
});

export type Stock = z.infer<typeof stock>;

/** 상품 재고. 재고 행이 없으면 ApiError(404) */
export async function getStock(productId: number): Promise<Stock> {
  return stock.parse(await authedRequest<unknown>(`/api/stocks/${productId}`));
}
