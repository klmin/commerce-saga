package com.klmin.commerce.orderservice.order.adapter.in.scheduler;

import com.klmin.commerce.orderservice.order.application.port.in.CleanupIdempotencyRecordsUseCase;
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
 * 멱등 기록 정리를 주기적으로 (PROGRESS 4-4절 ②). 실패하면 다음 주기에 다시 한다.
 * Debezium 모드(스케줄러 relay 꺼짐)에서도 돌아야 하므로 스케줄링을 여기서 따로 켠다
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "idempotency.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class IdempotencyCleanupScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final CleanupIdempotencyRecordsUseCase useCase;
    private final Duration interval;

    IdempotencyCleanupScheduler(CleanupIdempotencyRecordsUseCase useCase,
                                @Value("${idempotency.cleanup.interval:1h}") Duration interval) {
        this.useCase = useCase;
        this.interval = interval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::run, interval);
    }

    void run() {
        try {
            useCase.cleanup();
        } catch (RuntimeException e) {
            log.warn("멱등 기록 정리 실패, 다음 주기에 다시 시도", e);
        }
    }
}
