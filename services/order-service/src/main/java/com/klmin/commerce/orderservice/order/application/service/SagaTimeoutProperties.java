package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.domain.SagaTimeoutPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 멈춘 Saga 정리 (ADR 0007). 스캔 주기·켜고 끄기는 스케줄러 쪽 설정({@code saga.timeout.scan-interval}, {@code enabled}).
 *
 * @param stepTimeout 한 단계에서 응답을 기다리는 시간. 지나면 같은 커맨드를 다시 보낸다
 * @param maxResends  재고 예약·포인트 차감에서 다시 보내는 최대 횟수. 넘으면 반영됐다고 보고 역순 보상한다
 * @param maxPaymentResends 결제에서 다시 보내는 최대 횟수. 넘으면 결제 취소부터 보상한다(늦은 승인은 결제 서비스가 환불).
 *                    결제는 결과가 애매해 더 오래 기다린다. 보상 단계는 포기하지 않고 계속 다시 보낸다
 * @param batchSize   한 번 스캔에서 처리할 최대 Saga 수
 */
@ConfigurationProperties("saga.timeout")
public record SagaTimeoutProperties(
        @DefaultValue("30s") Duration stepTimeout,
        @DefaultValue("3") int maxResends,
        @DefaultValue("10") int maxPaymentResends,
        @DefaultValue("100") int batchSize) {

    public SagaTimeoutProperties {
        if (stepTimeout == null || stepTimeout.isNegative() || stepTimeout.isZero()) {
            throw new IllegalArgumentException("saga.timeout.step-timeout must be positive");
        }
        if (maxResends < 0 || maxPaymentResends < 0) {
            throw new IllegalArgumentException("saga.timeout.max-resends, max-payment-resends must not be negative");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("saga.timeout.batch-size must be positive");
        }
    }

    public SagaTimeoutPolicy policy() {
        return new SagaTimeoutPolicy(stepTimeout, maxResends, maxPaymentResends);
    }
}
