package com.klmin.commerce.authservice.auth.adapter.out.redis;

import com.klmin.commerce.authservice.auth.application.port.out.SsoSessionPort;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/** SSO 로그인 세션 (Spring Session Redis, {@code auth:session}). 만료된 세션은 찾지 못한다 */
@Component
class SpringSessionAdapter implements SsoSessionPort {

    private final SessionRepository<? extends Session> sessions;

    SpringSessionAdapter(SessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<Instant> lastAccessed(String sessionId) {
        Session session = sessions.findById(sessionId);
        return session == null || session.isExpired() ? Optional.empty() : Optional.of(session.getLastAccessedTime());
    }

    @Override
    public void delete(String sessionId) {
        sessions.deleteById(sessionId);
    }
}
