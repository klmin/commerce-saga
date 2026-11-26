import "server-only";

import { z } from "zod";

import type { Product } from "@/lib/products";
import { type SearchCriteria, searchQuery } from "@/lib/search";

import { authedRequest } from "./authed";

const result = z.object({
  items: z.array(
    z.object({
      id: z.number().int(),
      name: z.string(),
      description: z.string(),
      price: z.number().int(),
      onSale: z.boolean(),
    }),
  ),
  total: z.number().int(),
});

/** 한 번에 보여 줄 검색 결과 수 (search-service size 최대 100) */
const SEARCH_SIZE = 50;

/**
 * 상품 검색 (search-service, ADR 0010). 판매 중인 상품만. 결과는 CDC 로 받은 읽기 모델이라 수 초 늦을 수 있고,
 * 가격은 주문 때 다시 확인한다. 검색 저장소 장애면 ApiError(503)
 */
export async function searchProducts(
  criteria: SearchCriteria,
): Promise<{ items: Product[]; total: number }> {
  return result.parse(
    await authedRequest<unknown>(`/api/search/products?${searchQuery(criteria, SEARCH_SIZE)}`),
  );
}
