package com.klmin.commerce.orderservice.order.adapter.in.scheduler;

import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaTimeoutUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.util.UUID;

/**
 * 멈춘 Saga 를 주기적으로 찾아 정리한다 (ADR 0007). Saga 하나마다 따로 트랜잭션이라 하나가 실패해도 나머지는 처리된다.
 * <p>
 * 스케줄러 relay 를 끈 Debezium 모드에서도 돌아야 하므로 스케줄링을 여기서 따로 켠다.
 * 인스턴스가 여럿이어도 Saga 행 락과 {@code updatedAt} 재확인으로 같은 Saga 를 두 번 처리하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "saga.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
class SagaTimeoutScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutScheduler.class);

    private final HandleSagaTimeoutUseCase useCase;
    private final Duration scanInterval;

    SagaTimeoutScheduler(HandleSagaTimeoutUseCase useCase,
                         @Value("${saga.timeout.scan-interval:5s}") Duration scanInterval) {
        this.useCase = useCase;
        this.scanInterval = scanInterval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::scan, scanInterval);
    }

    void scan() {
        for (UUID orderId : useCase.findStuckSagas()) {
            try {
                useCase.handleTimeout(orderId);
            } catch (RuntimeException e) {
                // 다음 스캔에서 다시 시도한다 (DB 일시 장애, 동시 변경 등)
                log.warn("Saga 타임아웃 처리 실패, 다음 스캔에서 재시도 (orderId={})", orderId, e);
            }
        }
    }
}
