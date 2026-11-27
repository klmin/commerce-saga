import { z } from "zod";

import { api } from "@commerce/bff/api";

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
  return z.array(payment).parse(await api<unknown>(`/api/payments?${params}`));
}

const notification = z.object({
  // 알림 id: UUIDv7 문자열 (ADR 0018)
  id: z.string(),
  orderId: z.string(),
  // OrderCompleted·OrderCancelled (order.events)
  type: z.string(),
  message: z.string(),
  createdAt: z.string(),
});

export type Notification = z.infer<typeof notification>;

export const NOTIFICATIONS_PAGE_SIZE = 20;

/** 내 알림 (최신순). 다음 쪽은 마지막 id(UUIDv7, id 순 = 시간 순)를 before 로 */
export async function myNotifications(before?: string): Promise<Notification[]> {
  const params = new URLSearchParams({ size: String(NOTIFICATIONS_PAGE_SIZE) });
  if (before) {
    params.set("before", before);
  }
  return z.array(notification).parse(await api<unknown>(`/api/notifications/me?${params}`));
}
