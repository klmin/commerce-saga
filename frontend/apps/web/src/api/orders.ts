import { z } from "zod";

import { api } from "@commerce/bff/api";

const line = z.object({
  productId: z.number().int(),
  quantity: z.number().int(),
  unitPrice: z.number().int(),
});

const orderStatus = z.enum(["PENDING", "COMPLETED", "CANCELLED"]);

const order = z.object({
  orderId: z.string(),
  status: orderStatus,
  totalAmount: z.number().int(),
  pointAmount: z.number().int(),
  paidAmount: z.number().int(),
  cancelReason: z.string().nullable(),
  lines: z.array(line),
  createdAt: z.string(),
  updatedAt: z.string(),
});

const sagaStep = z.object({
  event: z.string(),
  state: z.string(),
  reason: z.string().nullable(),
  occurredAt: z.string(),
});

const saga = z.object({
  orderId: z.string(),
  state: z.string(),
  finished: z.boolean(),
  compensating: z.boolean(),
  pointUsed: z.boolean(),
  failureReason: z.string().nullable(),
  history: z.array(sagaStep),
});

export type Order = z.infer<typeof order>;
export type OrderLine = z.infer<typeof line>;
export type OrderSaga = z.infer<typeof saga>;

export type PlaceOrderInput = {
  lines: OrderLine[];
  pointAmount: number;
  /** 장바구니 주문이면 true: 주문한 상품이 장바구니에서 빠진다 (주문과 같은 트랜잭션) */
  fromCart?: boolean;
};

export const ORDERS_PAGE_SIZE = 20;

/**
 * 주문 생성. 응답 시점에는 PENDING 이고 Saga 는 비동기로 진행된다.
 * 같은 idempotencyKey 로 다시 보내면 새로 만들지 않고 처음 주문을 돌려준다(시나리오 10). 같은 키에 내용이 다르면 ApiError(422)
 */
export async function placeOrder(input: PlaceOrderInput, idempotencyKey: string): Promise<Order> {
  return order.parse(
    await api<unknown>("/api/orders", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(input),
    }),
  );
}

/** 내 주문 (최신순). 다음 쪽은 마지막 orderId 를 before 로 */
export async function myOrders(before?: string): Promise<Order[]> {
  const params = new URLSearchParams({ size: String(ORDERS_PAGE_SIZE) });
  if (before) {
    params.set("before", before);
  }
  return z.array(order).parse(await api<unknown>(`/api/orders?${params}`));
}

/** 남의 주문이거나 없으면 ApiError(404) */
export async function getOrder(orderId: string): Promise<Order> {
  return order.parse(await api<unknown>(`/api/orders/${encodeURIComponent(orderId)}`));
}

export async function getOrderSaga(orderId: string): Promise<OrderSaga> {
  return saga.parse(await api<unknown>(`/api/orders/${encodeURIComponent(orderId)}/saga`));
}
