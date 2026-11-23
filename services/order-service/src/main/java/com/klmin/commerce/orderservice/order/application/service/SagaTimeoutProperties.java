package com.klmin.commerce.orderservice.order.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 멈춘 Saga 정리 (ADR 0007). 스캔 주기·켜고 끄기는 스케줄러 쪽 설정({@code saga.timeout.scan-interval}, {@code enabled}).
 *
 * @param stepTimeout 한 단계에서 응답을 기다리는 시간. 지나면 같은 커맨드를 다시 보낸다
 * @param maxResends  정방향 단계(재고 예약·포인트 차감)에서 다시 보내는 최대 횟수. 넘으면 보상한다.
 *                    결제와 보상 단계는 포기하지 않고 계속 다시 보낸다
 * @param batchSize   한 번 스캔에서 처리할 최대 Saga 수
 */
@ConfigurationProperties("saga.timeout")
public record SagaTimeoutProperties(
        @DefaultValue("30s") Duration stepTimeout,
        @DefaultValue("3") int maxResends,
        @DefaultValue("100") int batchSize) {

    public SagaTimeoutProperties {
        if (stepTimeout == null || stepTimeout.isNegative() || stepTimeout.isZero()) {
            throw new IllegalArgumentException("saga.timeout.step-timeout must be positive");
        }
        if (maxResends < 0) {
            throw new IllegalArgumentException("saga.timeout.max-resends must not be negative");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("saga.timeout.batch-size must be positive");
        }
    }
}
