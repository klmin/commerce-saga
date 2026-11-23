package com.klmin.commerce.paymentservice.payment.application.port.out;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentStatsResult;

import java.time.Instant;

/** 결제 보정 대상 통계 조회 (읽기 전용 집계) */
public interface LoadPaymentStatsPort {

    PaymentStatsResult loadPaymentStats(Instant now);
}
