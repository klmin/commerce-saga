import { z } from "zod";

import { api } from "@/lib/api";

const me = z.object({ userId: z.number().int(), roles: z.array(z.string()) });

export type Me = z.infer<typeof me>;

/** 지금 로그인한 사용자 (Gateway 가 BFF 세션으로 답한다). 로그인하지 않았으면 api() 가 로그인을 시작한다 */
export async function getMe(): Promise<Me> {
  return me.parse(await api<unknown>("/api/me"));
}
