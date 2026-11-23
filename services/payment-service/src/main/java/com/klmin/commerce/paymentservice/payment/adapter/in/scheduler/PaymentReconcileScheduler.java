package com.klmin.commerce.paymentservice.payment.adapter.in.scheduler;

import com.klmin.commerce.paymentservice.payment.application.port.in.ReconcilePaymentsUseCase;
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
 * 결제 보정을 주기적으로 돌린다 (PG 조회로 UNKNOWN 확정, 취소된 주문의 승인 결제 환불, ADR 0007).
 * 결제 하나마다 따로 처리해 하나가 실패해도(PG 일시 장애 등) 나머지는 처리되고, 실패한 것은 다음 주기에 다시 한다.
 * 스케줄러 relay 를 끈 Debezium 모드에서도 돌아야 하므로 스케줄링을 여기서 따로 켠다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconcileScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconcileScheduler.class);

    private final ReconcilePaymentsUseCase useCase;
    private final Duration interval;

    PaymentReconcileScheduler(ReconcilePaymentsUseCase useCase,
                              @Value("${payment.reconcile.interval:2s}") Duration interval) {
        this.useCase = useCase;
        this.interval = interval;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::run, interval);
    }

    void run() {
        for (UUID orderId : useCase.findPending()) {
            try {
                useCase.reconcile(orderId);
            } catch (RuntimeException e) {
                log.warn("결제 보정 실패, 다음 주기에 재시도 (orderId={})", orderId, e);
            }
        }
    }
}
