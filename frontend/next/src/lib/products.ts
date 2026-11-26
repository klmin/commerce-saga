// 상품·가격은 product-service 가 정한다 (후속 4-1, ADR 0016). 목록은 lib/api/products.ts 로 읽는다.
// 화면 가격은 안내용이다: 주문할 때 본 가격을 함께 보내고, 서버 가격과 다르면 409 로 지금 가격을 받아 다시 확인을 받는다
export type Product = {
  id: number;
  name: string;
  price: number;
  /** 시연용 설명 (없으면 빈 문자열) */
  description: string;
  /** false 면 판매 중지 (주문하면 422) */
  onSale: boolean;
};

/** 상품 id → 이름. 이름을 못 읽었으면 "상품 #id" */
export function productName(id: number, names?: Readonly<Record<number, string>>): string {
  return names?.[id] ?? `상품 #${id}`;
}

export function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
