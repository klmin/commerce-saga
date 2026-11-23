package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

record OrderSummaryResponse(
        UUID orderId,
        String status,
        long totalAmount,
        long pointAmount,
        long paidAmount,
        String cancelReason,
        List<OrderResponse.Line> lines,
        Instant createdAt,
        Instant updatedAt) {

    static OrderSummaryResponse from(OrderSummaryResult s) {
        return new OrderSummaryResponse(s.orderId(), s.status(), s.totalAmount(), s.pointAmount(), s.paidAmount(),
                s.cancelReason(),
                s.lines().stream().map(l -> new OrderResponse.Line(l.productId(), l.quantity(), l.unitPrice())).toList(),
                s.createdAt(), s.updatedAt());
    }
}
