package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.Objects;
import java.util.UUID;

/** @param userId 요청한 사용자. 본인 결제만 조회할 수 있다 */
public record GetPaymentQuery(UUID orderId, UUID userId) {

    public GetPaymentQuery {
        Objects.requireNonNull(orderId, "orderId");
    }
}
