package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.domain.Order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OrderResponse(
        UUID orderId,
        UUID userId,
        String status,
        long totalAmount,
        long pointAmount,
        long paidAmount,
        String cancelReason,
        List<Line> lines,
        Instant createdAt,
        Instant updatedAt) {

    record Line(UUID productId, int quantity, long unitPrice) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.userId().value(), order.status().name(),
                order.totalAmount().value(), order.pointAmount().value(), order.paidAmount().value(), order.cancelReason(),
                order.lines().stream().map(l -> new Line(l.productId().value(), l.quantity().value(), l.unitPrice().value())).toList(),
                order.createdAt(), order.updatedAt());
    }
}
