package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.Objects;
import java.util.UUID;

public record PayCommand(UUID orderId, UUID userId, long amount) {

    public PayCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
