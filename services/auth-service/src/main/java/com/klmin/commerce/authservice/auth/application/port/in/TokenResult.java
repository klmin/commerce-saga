package com.klmin.commerce.authservice.auth.application.port.in;

import java.time.Duration;
import java.time.Instant;

/**
 * 로그인·refresh 결과.
 *
 * @param refreshToken       새 refresh token. 동시 refresh 유예(GRACE)로 회전하지 않았으면 null (쿠키를 바꾸지 않는다)
 * @param refreshTokenMaxAge 쿠키 수명 (refreshToken 이 null 이면 의미 없음)
 */
public record TokenResult(String accessToken, Instant accessTokenExpiresAt, String refreshToken, Duration refreshTokenMaxAge) {

    @Override
    public String toString() {
        return "TokenResult[accessTokenExpiresAt=" + accessTokenExpiresAt + ", rotated=" + (refreshToken != null) + "]";
    }
}
