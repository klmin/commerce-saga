package com.klmin.commerce.contract.payment;

import java.util.UUID;

/** 응답(payment -> order): 결제 거절. 돈이 나가지 않았다 */
public record PaymentFailed(UUID orderId, String reason) {

    public static final String TYPE = "PaymentFailed";
}
