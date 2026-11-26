import "server-only";

import { z } from "zod";

import { authedRequest } from "./authed";

const notification = z.object({
  id: z.number().int(),
  orderId: z.string(),
  // OrderCompleted·OrderCancelled (order.events)
  type: z.string(),
  message: z.string(),
  createdAt: z.string(),
});

export type Notification = z.infer<typeof notification>;

export const NOTIFICATIONS_PAGE_SIZE = 20;

/** 내 알림 (최신순). 알림 id 는 숫자라 다음 쪽은 마지막 id 를 before 로 */
export async function myNotifications(before?: number): Promise<Notification[]> {
  const params = new URLSearchParams({ size: String(NOTIFICATIONS_PAGE_SIZE) });
  if (before !== undefined) {
    params.set("before", String(before));
  }
  return z
    .array(notification)
    .parse(await authedRequest<unknown>(`/api/notifications/me?${params}`));
}
