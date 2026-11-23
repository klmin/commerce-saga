// 상품 카탈로그는 아직 서비스가 없다 (product-service 는 후속 4). 재고 시드(inventory V2)와 같은 상품 1~5 를 고정으로 둔다.
// 가격은 주문할 때 이 값을 그대로 보낸다(unitPrice). 서버가 가격을 정하지 않는 것은 한계로 적어 둔다 (PROGRESS 준비 메모 #5)
export type Product = {
  id: number;
  name: string;
  price: number;
  /** 시연용 설명 (없으면 빈 문자열) */
  description: string;
};

export const PRODUCTS: readonly Product[] = [
  { id: 1, name: "무선 키보드", price: 39_000, description: "" },
  { id: 2, name: "무선 마우스", price: 25_000, description: "" },
  { id: 3, name: "USB-C 허브", price: 18_000, description: "" },
  {
    id: 4,
    name: "한정판 키캡",
    price: 12_000,
    description: "재고 1개로 시작 (동시 주문·품절 시연)",
  },
  {
    id: 5,
    name: "단종 모니터암",
    price: 45_000,
    description: "재고 0개 (재고 예약 실패 → 주문 취소 시연)",
  },
];

export function productById(id: number): Product | undefined {
  return PRODUCTS.find((p) => p.id === id);
}

export function productName(id: number): string {
  return productById(id)?.name ?? `상품 #${id}`;
}

export function won(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
