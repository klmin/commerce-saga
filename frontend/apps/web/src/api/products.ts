import { z } from "zod";

import { api, ApiError } from "@commerce/bff/api";
import { isProductId, type Product, type ProductId } from "@/lib/products";
import { type SearchCriteria, searchQuery } from "@/lib/search";

// null·없는 값은 없음(undefined)으로 (Product 타입)
const optionalText = z
  .string()
  .nullish()
  .transform((v) => v ?? undefined);

const productFields = {
  id: z.uuid(),
  name: z.string(),
  description: z.string(),
  price: z.number().int(),
  onSale: z.boolean(),
  imagePath: optionalText,
};

/** product-service 상품 (ADR 0021: categoryCode·imagePath) */
const product = z.object({ ...productFields, categoryCode: optionalText });

/** search-service 결과 항목: 카테고리 필드 이름이 category (9-3) */
const searchItem = z
  .object({ ...productFields, category: optionalText })
  .transform(({ category, ...rest }) => ({ ...rest, categoryCode: category }));

const productList = z.object({ items: z.array(product) });

const category = z.object({ code: z.string(), name: z.string() });

export type Category = z.infer<typeof category>;

/** 카테고리 고정 목록 (정해 둔 순서, ADR 0021). 공개 GET, 바뀌지 않아 한 번 읽고 둔다 */
export async function listCategories(): Promise<Category[]> {
  return z.array(category).parse(await api<unknown>("/api/products/categories"));
}

/** 화면에 한 번에 보여 줄 상품 수 (product-service 한 쪽 최대) */
const LIST_SIZE = 100;
/** 여러 건 조회 한 번의 최대 id 수 (product-service GetProductUseCase.MAX_IDS) */
const MAX_IDS = 50;
/** 한 번에 보여 줄 검색 결과 수 (search-service size 최대 100) */
const SEARCH_SIZE = 50;

/** 판매 중인 상품 (id 순) */
export async function listProducts(): Promise<Product[]> {
  return productList.parse(await api<unknown>(`/api/products?size=${LIST_SIZE}`)).items;
}

/**
 * 주문 목록·상세에 쓸 상품 이름. 이름을 못 읽어도 화면은 그린다("상품 #id 끝 8자").
 * 주문에는 주문 시점 가격만 남고 이름은 지금 상품에서 읽는다
 */
export async function productNames(
  ids: readonly (ProductId | undefined)[],
): Promise<Record<ProductId, string>> {
  const products = await productsByIds(ids);
  return Object.fromEntries(Object.values(products).map((p) => [p.id, p.name]));
}

/** 상품 하나 (판매 중지 포함, 상세 화면). 없으면 ApiError(404) */
export async function getProduct(productId: ProductId): Promise<Product> {
  if (!isProductId(productId)) {
    throw new Error(`invalid product id: ${String(productId)}`);
  }
  return product.parse(await api<unknown>(`/api/products/${encodeURIComponent(productId)}`));
}

/**
 * 여러 상품 (장바구니·내 주문의 이름·이미지). 못 읽으면 빈 값: 화면은 이름 대신 "상품 #id", 이미지 없이 그린다
 */
export async function productsByIds(
  ids: readonly (ProductId | undefined)[],
): Promise<Record<ProductId, Product>> {
  const unique = [...new Set(ids)].filter(isProductId).slice(0, MAX_IDS);
  if (unique.length === 0) {
    return {};
  }
  try {
    const items = productList.parse(
      await api<unknown>(`/api/products?ids=${unique.join(",")}`),
    ).items;
    return Object.fromEntries(items.map((p) => [p.id, p]));
  } catch (e) {
    if (!(e instanceof ApiError)) {
      console.error("products by ids failed", e);
    }
    return {};
  }
}

const searchResult = z.object({ items: z.array(searchItem), total: z.number().int() });

/**
 * 상품 검색 (search-service, ADR 0010). 판매 중인 상품만. 결과는 CDC 로 받은 읽기 모델이라 수 초 늦을 수 있고,
 * 가격은 주문 때 다시 확인한다. 검색 저장소 장애면 ApiError(503)
 */
export async function searchProducts(
  criteria: SearchCriteria,
): Promise<{ items: Product[]; total: number }> {
  return searchResult.parse(
    await api<unknown>(`/api/search/products?${searchQuery(criteria, SEARCH_SIZE)}`),
  );
}

const stock = z.object({
  productId: z.uuid(),
  available: z.number().int(),
  reserved: z.number().int(),
});

/** 상품 id → 주문 가능한 재고. 한 상품을 못 읽어도 나머지는 보여 준다 (그 상품은 "확인 불가") */
export async function stocksByProduct(
  products: readonly Pick<Product, "id">[],
): Promise<Record<ProductId, number | undefined>> {
  const results = await Promise.allSettled(
    products.map(async (p) =>
      stock.parse(await api<unknown>(`/api/stocks/${encodeURIComponent(p.id)}`)),
    ),
  );
  return Object.fromEntries(
    products.map((p, i) => {
      const r = results[i];
      if (r.status === "fulfilled") {
        return [p.id, r.value.available];
      }
      if (!(r.reason instanceof ApiError)) {
        console.error(`stock ${p.id} failed`, r.reason);
      }
      return [p.id, undefined];
    }),
  );
}
