package com.klmin.commerce.pointservice.point.application.port.in;

import java.util.Objects;
import java.util.UUID;

public record UsePointCommand(UUID orderId, UUID userId, long amount) {

    public UsePointCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
