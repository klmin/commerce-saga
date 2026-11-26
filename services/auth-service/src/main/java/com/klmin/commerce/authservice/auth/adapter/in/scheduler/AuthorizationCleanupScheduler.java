package com.klmin.commerce.authservice.auth.adapter.in.scheduler;

import com.klmin.commerce.authservice.auth.application.port.in.ManageAuthorizationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

/**
 * 끝난 SAS 인가 기록을 주기적으로 지운다 (ADR 0017, 멱등 기록 정리 PR #74 와 같은 모양). 실패하면 다음 주기에 다시 한다.
 * 여러 인스턴스가 동시에 돌아도 같은 조건의 delete 라 안전하다
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "auth.oauth.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class AuthorizationCleanupScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationCleanupScheduler.class);

    private final ManageAuthorizationsUseCase useCase;
    private final Duration interval;

    AuthorizationCleanupScheduler(ManageAuthorizationsUseCase useCase,
                                  @Value("${auth.oauth.cleanup.interval:1h}") Duration interval) {
        this.useCase = useCase;
        this.interval = interval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::run, interval);
    }

    void run() {
        try {
            useCase.cleanupExpired();
        } catch (RuntimeException e) {
            log.warn("인가 기록 정리 실패, 다음 주기에 다시 시도", e);
        }
    }
}
