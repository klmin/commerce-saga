import "server-only";

import { z } from "zod";

import { authedRequest } from "./authed";

const payment = z.object({
  paymentId: z.string(),
  orderId: z.string(),
  amount: z.number().int(),
  // APPROVED·DECLINED·UNKNOWN·CANCELLED·REFUNDED
  status: z.string(),
  reason: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export type Payment = z.infer<typeof payment>;

export const PAYMENTS_PAGE_SIZE = 20;

/** 내 결제 (최신순). 다음 쪽은 마지막 paymentId 를 before 로 */
export async function myPayments(before?: string): Promise<Payment[]> {
  const params = new URLSearchParams({ size: String(PAYMENTS_PAGE_SIZE) });
  if (before) {
    params.set("before", before);
  }
  return z.array(payment).parse(await authedRequest<unknown>(`/api/payments?${params}`));
}
