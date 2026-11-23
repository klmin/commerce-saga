package com.klmin.commerce.paymentservice.payment.application.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 결제 보정 (ADR 0007). 주기·켜고 끄기는 스케줄러 쪽 설정({@code payment.reconcile.interval}, {@code enabled}).
 *
 * @param notFoundAfter PG 조회에 거래가 계속 없으면 이 시간(결제 기록 생성부터) 뒤 PG 가 요청을 받지 못한 것으로 보고
 *                      거절로 확정한다. PG 의 최대 처리 시간보다 길어야 한다 (가짜 PG 지연 최대 60초)
 * @param batchSize     한 번에 보정할 최대 결제 수
 */
@ConfigurationProperties("payment.reconcile")
public record PaymentReconcileProperties(
        @DefaultValue("2m") Duration notFoundAfter,
        @DefaultValue("100") int batchSize) {

    public PaymentReconcileProperties {
        if (notFoundAfter == null || notFoundAfter.isNegative() || notFoundAfter.isZero()) {
            throw new IllegalArgumentException("payment.reconcile.not-found-after must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("payment.reconcile.batch-size must be positive");
        }
    }
}
