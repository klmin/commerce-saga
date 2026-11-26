import { z } from "zod";

import { api, ApiError } from "@/lib/api";

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

export type PointHistoryEntry = z.infer<typeof pointHistoryEntry>;

export const POINT_HISTORY_PAGE_SIZE = 20;

/** 내 포인트 잔액. 가입 직후 계정이 아직 없으면(404, 몇 초 안에 생김, 시나리오 13) null */
export async function myPointBalance(): Promise<number | null> {
  try {
    return pointBalance.parse(await api<unknown>("/api/points/me")).balance;
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}

/** 내 포인트 내역 (주문 사용 + 지급, 최신순). 다음 쪽은 마지막 entryId 를 before 로 */
export async function myPointHistory(before?: string): Promise<PointHistoryEntry[]> {
  const params = new URLSearchParams({ size: String(POINT_HISTORY_PAGE_SIZE) });
  if (before) {
    params.set("before", before);
  }
  return z.array(pointHistoryEntry).parse(await api<unknown>(`/api/points/me/history?${params}`));
}
