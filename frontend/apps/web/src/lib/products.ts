import { shortId } from "@commerce/bff/short-id";

// 상품·가격은 product-service 가 정한다 (후속 4-1, ADR 0016). 목록은 lib/api/products.ts 로 읽는다.
// 화면 가격은 안내용이다: 주문할 때 본 가격을 함께 보내고, 서버 가격과 다르면 409 로 지금 가격을 받아 다시 확인을 받는다
/** 상품 id: UUID(v7) 문자열 (ADR 0018) */
export type ProductId = string;

const PRODUCT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** URL 등 밖에서 온 값이 상품 id(UUID) 형식인지 */
export function isProductId(value: unknown): value is ProductId {
  return typeof value === "string" && PRODUCT_ID.test(value);
}

export type Product = {
  id: ProductId;
  name: string;
  price: number;
  /** 시연용 설명 (없으면 빈 문자열) */
  description: string;
  /** false 면 판매 중지 (주문하면 422) */
  onSale: boolean;
  /** 카테고리 코드 (ADR 0021). 모르면(옛 색인·장바구니 등에서 만든 값) 없음 */
  categoryCode?: string;
  /** 대표 이미지 읽기 경로(서비스 기준 /products/{id}/images/{파일}). 없으면 없음 */
  imagePath?: string;
};

/** 상품 id → 이름. 이름을 못 읽었으면 "상품 #id 끝 8자" (UUID 전체는 길고, UUIDv7 앞부분은 시각이라 겹친다) */
export function productName(id: ProductId, names?: Readonly<Record<ProductId, string>>): string {
  return names?.[id] ?? `상품 #${shortId(id)}`;
}

/** 서비스 기준 이미지 경로 → 화면이 부를 주소 (같은 출처 Gateway /api). 다른 모양은 쓰지 않는다 */
export function imageUrl(imagePath: string | undefined): string | undefined {
  return imagePath?.startsWith("/products/") ? `/api${imagePath}` : undefined;
}

export function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
