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
        Instant updatedAt,
        // 사용자가 취소를 요청한 시각 (없으면 null). 진행 중이면 화면이 "취소 요청됨"을 보인다 (ADR 0020)
        Instant cancelRequestedAt) {

    record Line(UUID productId, int quantity, long unitPrice) {
    }

    static OrderResponse from(Order order) {
        return new OrderResponse(order.id(), order.userId().value(), order.status().name(),
                order.totalAmount().value(), order.pointAmount().value(), order.paidAmount().value(), order.cancelReason(),
                order.lines().stream().map(l -> new Line(l.productId().value(), l.quantity().value(), l.unitPrice().value())).toList(),
                order.createdAt(), order.updatedAt(), order.cancelRequestedAt());
    }
}
