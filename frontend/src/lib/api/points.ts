import "server-only";

import { z } from "zod";

import { authedRequest } from "./authed";

const pointBalance = z.object({ userId: z.number().int(), balance: z.number().int() });

const pointHistoryEntry = z.object({
  entryId: z.string(),
  // ORDER(주문 사용) 또는 지급 종류(SIGNUP_BONUS)
  kind: z.string(),
  orderId: z.string().nullable(),
  amount: z.number().int(),
  // 주문 사용: USED·REJECTED·REFUNDED, 지급: GRANTED
  status: z.string(),
  reason: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export type PointBalance = z.infer<typeof pointBalance>;
export type PointHistoryEntry = z.infer<typeof pointHistoryEntry>;

export const POINT_HISTORY_PAGE_SIZE = 20;

/** 내 포인트 잔액. 계정이 아직 없으면(가입 직후 몇 초) ApiError(404) */
export async function myPoints(): Promise<PointBalance> {
  return pointBalance.parse(await authedRequest<unknown>("/api/points/me"));
}

/** 내 포인트 내역 (주문 사용 + 지급, 최신순). 다음 쪽은 마지막 entryId 를 before 로 */
export async function myPointHistory(before?: string): Promise<PointHistoryEntry[]> {
  const params = new URLSearchParams({ size: String(POINT_HISTORY_PAGE_SIZE) });
  if (before) {
    params.set("before", before);
  }
  return z
    .array(pointHistoryEntry)
    .parse(await authedRequest<unknown>(`/api/points/me/history?${params}`));
}
