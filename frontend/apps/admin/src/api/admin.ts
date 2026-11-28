import { api, ApiError } from "@commerce/bff/api";
import { shortId } from "@commerce/bff/short-id";
import { z } from "zod";

import { FAULT_MODES, type FaultSettings } from "@/lib/fault-input";
import type { StockAdjustInput } from "@/lib/stock-input";
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
  // ADR 0021. 카테고리 코드(이름은 listCategories), 대표 이미지 읽기 경로(서비스 기준, 없으면 null)
  categoryCode: z.string(),
  imagePath: z.string().nullable(),
});

export type Product = z.infer<typeof product>;

const productPage = z.object({ items: z.array(product), hasNext: z.boolean() });

const snapshot = z.object({
  name: z.string().nullable(),
  description: z.string().nullable(),
  price: z.number().int().nullable(),
  onSale: z.boolean().nullable(),
  // 그 열(ADR 0021) 전의 이력이면 null
  categoryCode: z
    .string()
    .nullish()
    .transform((v) => v ?? null),
  imagePath: z
    .string()
    .nullish()
    .transform((v) => v ?? null),
});

const auditPage = z.object({
  items: z.array(
    z.object({
      action: z.enum(AUDIT_ACTIONS),
      before: snapshot.nullable(),
      after: snapshot,
      // 바꾼 관리자 userId: UUID 문자열 (ADR 0018)
      changedBy: z.string(),
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

/** 화면에 보일 짧은 id (UUID 끝 8자, UUIDv7 앞부분은 시각이라 겹친다). 전체 값은 title 등으로 함께 둔다 */
export function shortProductId(productId: ProductId): string {
  return shortId(productId);
}

/** URL 경로 조각. UUID 가 아니면 요청하지 않는다(다른 경로로 새지 않게) */
function idPath(productId: ProductId): string {
  if (!isProductId(productId)) {
    throw new Error(`invalid product id: ${String(productId)}`);
  }
  return encodeURIComponent(productId);
}

const category = z.object({ code: z.string(), name: z.string() });

export type Category = z.infer<typeof category>;

/** 카테고리 고정 목록 (정해 둔 순서, ADR 0021). 공개 GET */
export async function listCategories(): Promise<Category[]> {
  return z.array(category).parse(await api<unknown>("/api/products/categories"));
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

/**
 * 대표 이미지 올리기 (multipart, 필드 file, ADR 0021). 서비스가 내용으로 형식(jpg·png·webp)·크기(2MB)를 다시 본다.
 * 형식이 아니면 ApiError(400), 저장소가 멈췄으면 ApiError(503) (상품은 그대로)
 */
export async function uploadProductImage(productId: ProductId, file: File): Promise<Product> {
  const body = new FormData();
  body.set("file", file);
  return product.parse(
    await api<unknown>(`/api/admin/products/${idPath(productId)}/image`, { method: "POST", body }),
  );
}

// --- 재고 조정 (inventory-service /admin/stocks, ADR 0019) ---

const stock = z.object({
  productId: z.uuid(),
  available: z.number().int(),
  reserved: z.number().int(),
});

export type Stock = z.infer<typeof stock>;

const adjustment = z.object({
  id: z.number().int(),
  delta: z.number().int(),
  availableAfter: z.number().int(),
  reason: z.string().nullable(),
  // 조정한 관리자 userId: UUID 문자열 (ADR 0018)
  adjustedBy: z.string(),
  adjustedAt: z.string(),
});

export type StockAdjustment = z.infer<typeof adjustment>;

const adjustResult = stock.extend({ adjustment });

const adjustmentPage = z.object({ items: z.array(adjustment), hasNext: z.boolean() });

/** 한 화면에 보여 줄 재고 조정 이력 수 (최근부터) */
export const STOCK_HISTORY_SIZE = 20;

/** 지금 재고. 재고 행이 없으면(아직 입고하지 않은 새 상품) null */
export async function getStock(productId: ProductId): Promise<Stock | null> {
  try {
    return stock.parse(await api<unknown>(`/api/stocks/${idPath(productId)}`));
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}

/**
 * 입고(+)·차감(-). idempotencyKey 는 화면이 조정 한 번마다 만든다: 응답을 못 받고 다시 보내도 한 번만 반영된다.
 * 가용 재고보다 많이 빼면 ApiError(409), 같은 키로 다른 내용을 보내면 ApiError(422)
 */
export async function adjustStock(
  productId: ProductId,
  input: StockAdjustInput,
  idempotencyKey: string,
): Promise<z.infer<typeof adjustResult>> {
  return adjustResult.parse(
    await api<unknown>(`/api/admin/stocks/${idPath(productId)}/adjustments`, {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    }),
  );
}

/** 재고 조정 이력 (최근부터). 재고 행이 없으면 빈 목록 */
export async function stockAdjustments(
  productId: ProductId,
): Promise<{ items: StockAdjustment[]; hasNext: boolean }> {
  return adjustmentPage.parse(
    await api<unknown>(
      `/api/admin/stocks/${idPath(productId)}/adjustments?size=${STOCK_HISTORY_SIZE}`,
    ),
  );
}
