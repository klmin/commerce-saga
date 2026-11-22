package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.domain.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OrderResponse(
        UUID orderId,
        long userId,
        String status,
        long totalAmount,
        String rejectReason,
        List<Line> lines,
        Instant createdAt,
        Instant updatedAt) {

    record Line(long productId, int quantity, long unitPrice) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.userId(), order.status().name(), order.totalAmount(),
                order.rejectReason(),
                order.lines().stream().map(l -> new Line(l.productId(), l.quantity(), l.unitPrice())).toList(),
                order.createdAt(), order.updatedAt());
    }
}
