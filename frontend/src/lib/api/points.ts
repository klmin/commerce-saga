import "server-only";

import { z } from "zod";

import { authedRequest } from "./authed";

const pointBalance = z.object({ userId: z.number().int(), balance: z.number().int() });

export type PointBalance = z.infer<typeof pointBalance>;

/** 내 포인트 잔액. 계정이 아직 없으면(가입 직후 몇 초) ApiError(404) */
export async function myPoints(): Promise<PointBalance> {
  return pointBalance.parse(await authedRequest<unknown>("/api/points/me"));
}
