package com.klmin.commerce.contract.payment;

import java.util.UUID;

/** 응답(payment -> order): 결제 승인 */
public record PaymentApproved(UUID orderId, UUID paymentId) {

    public static final String TYPE = "PaymentApproved";
}
