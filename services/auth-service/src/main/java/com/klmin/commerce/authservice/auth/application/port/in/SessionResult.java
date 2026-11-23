package com.klmin.commerce.authservice.auth.application.port.in;

import java.time.Instant;

/**
 * 로그인 기기 하나.
 *
 * @param sessionId refresh token family id. secret 이 없으면 이것만으로는 토큰을 만들 수 없다
 * @param current   이 요청을 보낸 기기인지
 */
public record SessionResult(String sessionId, String agent, Instant createdAt, Instant lastUsedAt, Instant expiresAt,
                            boolean current) {
}
