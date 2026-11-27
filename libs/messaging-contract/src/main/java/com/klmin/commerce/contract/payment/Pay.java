package com.klmin.commerce.contract.payment;

import java.util.UUID;

/** 커맨드(order -> payment): 이 주문의 남은 금액(amount)을 결제하라. 같은 주문으로 두 번 결제하지 않는다 */
public record Pay(UUID orderId, UUID userId, long amount) {

    public static final String TYPE = "Pay";
}
