package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** SSO 로그인 세션 저장소 ({@code AUTH_SESSION}, Spring Session) */
public interface SsoSessionPort {

    /** 살아 있으면 마지막 사용 시각 */
    Optional<Instant> lastAccessed(String sessionId);

    void delete(String sessionId);
}
