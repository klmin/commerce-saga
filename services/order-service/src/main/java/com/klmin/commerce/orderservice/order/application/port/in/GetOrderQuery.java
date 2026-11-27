package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.Objects;
import java.util.UUID;

/** @param userId 요청한 사용자. 본인 주문만 조회할 수 있다 */
public record GetOrderQuery(UUID orderId, UUID userId) {

    public GetOrderQuery {
        Objects.requireNonNull(orderId, "orderId");
    }
}
