import "server-only";

import { z } from "zod";

import {
  AUDIT_ACTIONS,
  type AuditEntry,
  type DescribeInput,
  type RegisterInput,
} from "@/lib/admin/product-input";
import type { Product } from "@/lib/products";

import { authedRequest } from "./authed";

// 관리자 상품 관리 (product-service /admin/products, 후속 4-3). ADMIN 이 아니면 Gateway 가 ApiError(403)

const product = z.object({
  id: z.number().int(),
  name: z.string(),
  description: z.string(),
  price: z.number().int(),
  onSale: z.boolean(),
});

const productPage = z.object({ items: z.array(product), hasNext: z.boolean() });

const snapshot = z.object({
  name: z.string().nullable(),
  description: z.string().nullable(),
  price: z.number().int().nullable(),
  onSale: z.boolean().nullable(),
});

const auditPage = z.object({
  items: z.array(
    z.object({
      action: z.enum(AUDIT_ACTIONS),
      before: snapshot.nullable(),
      after: snapshot,
      changedBy: z.number().int(),
      changedAt: z.string(),
    }),
  ),
  hasNext: z.boolean(),
});

/** 한 화면에 보여 줄 수 (product-service size 최대 100) */
export const ADMIN_PAGE_SIZE = 50;

/** 판매 중지 포함 전체 (id 순) */
export async function listAllProducts(
  page: number,
): Promise<{ items: Product[]; hasNext: boolean }> {
  return productPage.parse(
    await authedRequest<unknown>(`/api/admin/products?page=${page}&size=${ADMIN_PAGE_SIZE}`),
  );
}

/** 상품 하나 (판매 중지 포함). 없으면 ApiError(404) */
export async function getProduct(productId: number): Promise<Product> {
  return product.parse(await authedRequest<unknown>(`/api/products/${productId}`));
}

/** 관리 이력 (최근부터). 없는 상품이면 ApiError(404) */
export async function productAuditLog(
  productId: number,
  page: number,
): Promise<{ items: AuditEntry[]; hasNext: boolean }> {
  return auditPage.parse(
    await authedRequest<unknown>(
      `/api/admin/products/${productId}/audit-log?page=${page}&size=${ADMIN_PAGE_SIZE}`,
    ),
  );
}

export async function registerProduct(input: RegisterInput): Promise<Product> {
  return product.parse(
    await authedRequest<unknown>("/api/admin/products", {
      method: "POST",
      body: JSON.stringify(input),
    }),
  );
}

export async function describeProduct(productId: number, input: DescribeInput): Promise<Product> {
  return product.parse(
    await authedRequest<unknown>(`/api/admin/products/${productId}`, {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  );
}

export async function changeProductPrice(productId: number, price: number): Promise<Product> {
  return product.parse(
    await authedRequest<unknown>(`/api/admin/products/${productId}/price`, {
      method: "PUT",
      body: JSON.stringify({ price }),
    }),
  );
}

export async function changeProductSaleStatus(
  productId: number,
  onSale: boolean,
): Promise<Product> {
  return product.parse(
    await authedRequest<unknown>(`/api/admin/products/${productId}/sale`, {
      method: "PUT",
      body: JSON.stringify({ onSale }),
    }),
  );
}
