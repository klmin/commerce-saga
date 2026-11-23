import "server-only";

import { z } from "zod";

const schema = z.object({
  // Next 서버만 Gateway 를 부른다 (브라우저는 Next 만 본다). 로컬 기본값은 Gateway :8000
  BACKEND_URL: z.url({ protocol: /^https?$/ }).default("http://localhost:8000"),
  // 세션 쿠키 Secure. 브라우저는 localhost 에서는 http 여도 Secure 쿠키를 받는다. 다른 호스트를 http 로 볼 때만 false
  COOKIE_SECURE: z
    .enum(["true", "false"])
    .default("true")
    .transform((v) => v === "true"),
});

export type ServerEnv = z.infer<typeof schema>;

// 서버 전용 환경변수. 잘못된 값이면 어떤 변수가 문제인지 바로 알 수 있게 실패시킨다
export function serverEnv(source: Record<string, string | undefined> = process.env): ServerEnv {
  const result = schema.safeParse(source);
  if (!result.success) {
    const issues = result.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`).join(", ");
    throw new Error(`환경변수 설정 오류 - ${issues}`);
  }
  return result.data;
}
