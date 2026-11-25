package com.klmin.commerce.authservice.auth.application.service;

import com.klmin.commerce.authservice.auth.application.port.in.ManageAuthorizationsUseCase;
import com.klmin.commerce.authservice.auth.application.port.out.AuthorizationRecordPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * SAS 인가 기록 정리 (ADR 0017). 인가 기록은 로그인·갱신마다 쌓이므로 끝난 행을 주기적으로 지우고,
 * SSO 로그아웃 때는 그 사용자의 행을 바로 지운다.
 */
@Service
public class AuthorizationRecordService implements ManageAuthorizationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationRecordService.class);

    private final AuthorizationRecordPort records;
    private final Clock clock;
    private final Duration retention;

    public AuthorizationRecordService(AuthorizationRecordPort records, Clock clock,
                                @Value("${auth.oauth.authorization-retention:1d}") Duration retention) {
        this.records = records;
        this.clock = clock;
        this.retention = retention;
    }

    @Override
    @Transactional
    public void revokeAll(long userId) {
        int deleted = records.deleteByPrincipal(Long.toString(userId));
        log.info("SSO 로그아웃: 인가 기록 {}건 삭제 (userId={})", deleted, userId);
    }

    @Override
    @Transactional
    public int cleanupExpired() {
        int deleted = records.deleteExpiredBefore(clock.instant().minus(retention));
        if (deleted > 0) {
            log.info("끝난 인가 기록 {}건 삭제 (보존 {})", deleted, retention);
        }
        return deleted;
    }
}
