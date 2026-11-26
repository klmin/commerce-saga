// auth 요청을 보낸 브라우저 정보. Next 서버가 Gateway 에 대신 요청하므로 넘겨 주지 않으면
// Gateway 의 /auth/** IP rate limit 이 모든 사용자를 Next 서버 주소 하나로 본다 (ADR 0011)
export type ClientInfo = {
  /** 브라우저 IP. Gateway 는 믿는 프록시(BFF)에서 온 X-Forwarded-For 만 읽는다 */
  ip?: string;
  /** 로그인 기기 목록에 보일 이름 */
  userAgent?: string;
};

const IP_PATTERN = /^[0-9a-fA-F:.]{2,45}$/;

/**
 * 들어온 요청의 브라우저 IP. `X-Forwarded-For` 의 맨 오른쪽 값을 쓴다:
 * Next 앞에 프록시(Ingress)가 있으면 그 프록시가 본 연결 주소이고, 없으면 Next 가 소켓 주소로 채운 값이다.
 * 앞에 프록시 없이 브라우저가 직접 이 헤더를 보내면 그 값이 쓰이므로, 운영에서는 Next 앞에 프록시를 둔다.
 */
export function clientInfoFrom(headers: Headers): ClientInfo {
  const forwarded = headers.get("x-forwarded-for");
  const last = forwarded?.split(",").at(-1)?.trim();
  return {
    ip: last && IP_PATTERN.test(last) ? last : undefined,
    userAgent: headers.get("user-agent")?.slice(0, 200) || undefined,
  };
}
