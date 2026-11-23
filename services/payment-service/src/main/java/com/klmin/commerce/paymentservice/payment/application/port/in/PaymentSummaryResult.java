package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** 결제 목록 한 줄 (조회 전용 DTO). PG 거래 id 는 내보내지 않는다 */
public record PaymentSummaryResult(UUID paymentId, UUID orderId, long amount, String status, String reason,
                             Instant createdAt, Instant updatedAt) {
}
