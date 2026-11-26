import { z } from "zod";

import { api } from "./api";

const me = z.object({
  userId: z.number().int(),
  roles: z.array(z.string()),
  // 로그인 기기 화면 주소 (인증 화면, 후속 5-6). 옛 Gateway 는 보내지 않는다
  devicesUrl: z.string().optional(),
});

export type Me = z.infer<typeof me>;

/** 지금 로그인한 사용자 (Gateway 가 BFF 세션으로 답한다). 로그인하지 않았으면 api() 가 로그인을 시작한다 */
export async function getMe(): Promise<Me> {
  return me.parse(await api<unknown>("/api/me"));
}
