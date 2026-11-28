package com.klmin.commerce.orderservice.order.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 주문 목록 한 줄 (조회 전용 DTO, 애그리거트를 거치지 않는다) */
public record OrderSummaryResult(
        UUID orderId,
        String status,
        long totalAmount,
        long pointAmount,
        String cancelReason,
        List<Line> lines,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelRequestedAt) {

    public OrderSummaryResult {
        lines = List.copyOf(lines);
    }

    /** 취소 요청이 없는 주문 */
    public OrderSummaryResult(UUID orderId, String status, long totalAmount, long pointAmount, String cancelReason,
                              List<Line> lines, Instant createdAt, Instant updatedAt) {
        this(orderId, status, totalAmount, pointAmount, cancelReason, lines, createdAt, updatedAt, null);
    }

    public long paidAmount() {
        return totalAmount - pointAmount;
    }

    public record Line(UUID productId, int quantity, long unitPrice) {
    }
}
