package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.Objects;
import java.util.UUID;

/** 사용자 주문 취소 (ADR 0020). userId 는 토큰의 sub: 본인 주문만 취소할 수 있다 */
public record CancelOrderCommand(UUID orderId, UUID userId) {

    public CancelOrderCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(userId, "userId");
    }
}
