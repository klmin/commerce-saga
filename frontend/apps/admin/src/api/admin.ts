import { api } from "@commerce/bff/api";
import { z } from "zod";

import { FAULT_MODES, type FaultSettings } from "@/lib/fault-input";
import {
  AUDIT_ACTIONS,
  type AuditEntry,
  type DescribeInput,
  type RegisterInput,
} from "@/lib/product-input";

// 관리자 API (Gateway /api/admin/** = ADMIN 역할, 서비스도 토큰으로 다시 검사). ADMIN 이 아니면 ApiError(403)

const faultSettings = z.object({
  mode: z.enum(FAULT_MODES),
  delayMillis: z.number().int().nonnegative(),
});

/** 가짜 PG 장애 설정 (payment-service) */
export async function getPaymentFaults(): Promise<FaultSettings> {
  return faultSettings.parse(await api<unknown>("/api/admin/payment-faults"));
}

export async function setPaymentFaults(settings: FaultSettings): Promise<FaultSettings> {
  return faultSettings.parse(
    await api<unknown>("/api/admin/payment-faults", {
      method: "PUT",
      body: JSON.stringify(settings),
    }),
  );
}

// --- 상품 관리 (product-service /admin/products, 후속 4-3) ---

const product = z.object({
  id: z.uuid(),
  name: z.string(),
  description: z.string(),
  price: z.number().int(),
  onSale: z.boolean(),
});

export type Product = z.infer<typeof product>;

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

/** 상품 id: UUID(v7) 문자열 (ADR 0018) */
export type ProductId = string;

const PRODUCT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 상품 id 로 쓸 수 있는 값 (UUID 형식). URL 에서 온 값이라 믿지 않는다 */
export function isProductId(value: unknown): value is ProductId {
  return typeof value === "string" && PRODUCT_ID.test(value);
}

/** 화면에 보일 짧은 id (UUID 앞 8자). 전체 값은 title 등으로 함께 둔다 */
export function shortProductId(productId: ProductId): string {
  return productId.slice(0, 8);
}

/** URL 경로 조각. UUID 가 아니면 요청하지 않는다(다른 경로로 새지 않게) */
function idPath(productId: ProductId): string {
  if (!isProductId(productId)) {
    throw new Error(`invalid product id: ${String(productId)}`);
  }
  return encodeURIComponent(productId);
}

/** 판매 중지 포함 전체 (id 순) */
export async function listAllProducts(
  page: number,
): Promise<{ items: Product[]; hasNext: boolean }> {
  return productPage.parse(
    await api<unknown>(`/api/admin/products?page=${page}&size=${ADMIN_PAGE_SIZE}`),
  );
}

/** 상품 하나 (판매 중지 포함). 없으면 ApiError(404) */
export async function getProduct(productId: ProductId): Promise<Product> {
  return product.parse(await api<unknown>(`/api/products/${idPath(productId)}`));
}

/** 관리 이력 (최근부터). 없는 상품이면 ApiError(404) */
export async function productAuditLog(
  productId: ProductId,
  page: number,
): Promise<{ items: AuditEntry[]; hasNext: boolean }> {
  return auditPage.parse(
    await api<unknown>(
      `/api/admin/products/${idPath(productId)}/audit-log?page=${page}&size=${ADMIN_PAGE_SIZE}`,
    ),
  );
}

export async function registerProduct(input: RegisterInput): Promise<Product> {
  return product.parse(
    await api<unknown>("/api/admin/products", { method: "POST", body: JSON.stringify(input) }),
  );
}

export async function describeProduct(
  productId: ProductId,
  input: DescribeInput,
): Promise<Product> {
  return product.parse(
    await api<unknown>(`/api/admin/products/${idPath(productId)}`, {
      method: "PUT",
      body: JSON.stringify(input),
    }),
  );
}

export async function changeProductPrice(productId: ProductId, price: number): Promise<Product> {
  return product.parse(
    await api<unknown>(`/api/admin/products/${idPath(productId)}/price`, {
      method: "PUT",
      body: JSON.stringify({ price }),
    }),
  );
}

export async function changeProductSaleStatus(
  productId: ProductId,
  onSale: boolean,
): Promise<Product> {
  return product.parse(
    await api<unknown>(`/api/admin/products/${idPath(productId)}/sale`, {
      method: "PUT",
      body: JSON.stringify({ onSale }),
    }),
  );
}
