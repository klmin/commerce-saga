import "server-only";

import { unstable_rethrow } from "next/navigation";
import { z } from "zod";

import { logError } from "@/lib/log";
import type { Product } from "@/lib/products";

import { authedRequest } from "./authed";
import { ApiError } from "./client";

const product = z.object({
  id: z.number().int(),
  name: z.string(),
  description: z.string(),
  price: z.number().int(),
  onSale: z.boolean(),
});

const productList = z.object({ items: z.array(product) });

/** 화면에 한 번에 보여 줄 상품 수 (product-service 한 쪽 최대) */
const LIST_SIZE = 100;
/** 여러 건 조회 한 번의 최대 id 수 (product-service GetProductUseCase.MAX_IDS) */
const MAX_IDS = 50;

/** 판매 중인 상품 (id 순) */
export async function listProducts(): Promise<Product[]> {
  return productList.parse(await authedRequest<unknown>(`/api/products?size=${LIST_SIZE}`)).items;
}

/** 여러 상품 (판매 중지 포함, 없는 id 는 빠짐) */
export async function productsByIds(ids: readonly number[]): Promise<Product[]> {
  const unique = [...new Set(ids)].slice(0, MAX_IDS);
  if (unique.length === 0) {
    return [];
  }
  return productList.parse(await authedRequest<unknown>(`/api/products?ids=${unique.join(",")}`))
    .items;
}

/**
 * 주문 목록·상세에 쓸 상품 이름. 이름을 못 읽어도 화면은 그린다("상품 #id").
 * 주문에는 주문 시점 가격만 남고 이름은 지금 상품에서 읽는다
 */
export async function productNames(ids: readonly number[]): Promise<Record<number, string>> {
  try {
    return Object.fromEntries((await productsByIds(ids)).map((p) => [p.id, p.name]));
  } catch (e) {
    unstable_rethrow(e);
    if (!(e instanceof ApiError)) {
      logError("product names failed", e);
    }
    return {};
  }
}
