package com.klmin.commerce.authservice.auth.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * refresh token 은 httpOnly 쿠키로만 주고받는다 (ADR 0008). 브라우저 JS 가 읽을 수 없고,
 * {@code Path=/auth} 라 refresh·로그아웃·기기 관리 요청에만 실린다. {@code SameSite=Lax} 라 다른 사이트에서 보낸 POST 에는 실리지 않는다.
 *
 * @param secure https 에서만 보낸다. 로컬 http 로 확인할 때만 false ({@code AUTH_COOKIE_SECURE})
 */
@ConfigurationProperties("auth.cookie")
record RefreshCookie(boolean secure) {

    static final String NAME = "refresh_token";
    private static final String PATH = "/auth";

    String issue(String refreshToken, Duration maxAge) {
        return base(refreshToken).maxAge(maxAge).build().toString();
    }

    String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax").path(PATH);
    }
}
