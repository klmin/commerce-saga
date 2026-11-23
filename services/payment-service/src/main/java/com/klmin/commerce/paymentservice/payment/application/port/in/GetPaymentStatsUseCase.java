package com.klmin.commerce.paymentservice.payment.application.port.in;

/** 결제 보정 대상 통계. 메트릭(/actuator/prometheus)이 주기적으로 읽는다 (ADR 0013) */
public interface GetPaymentStatsUseCase {

    PaymentStatsResult getStats();
}
