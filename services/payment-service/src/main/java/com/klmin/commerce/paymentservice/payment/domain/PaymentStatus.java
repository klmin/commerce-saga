package com.klmin.commerce.paymentservice.payment.domain;

public enum PaymentStatus {
    /** PG 승인 */
    APPROVED,
    /** PG 거절 (돈이 나가지 않음) */
    DECLINED
}
