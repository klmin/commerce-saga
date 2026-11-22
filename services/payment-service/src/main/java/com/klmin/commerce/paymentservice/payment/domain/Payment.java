package com.klmin.commerce.paymentservice.payment.domain;

import com.klmin.commerce.paymentservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 주문 하나의 결제 결과 애그리거트 (orderId 당 하나). PG 결과를 그대로 기록하며 이후 바뀌지 않는다 */
public final class Payment {

    public static final int REASON_MAX_LENGTH = 200;

    private final UUID id;
    private final UUID orderId;
    private final long userId;
    private final long amount;
    private final PaymentStatus status;
    private final String transactionId;
    private final String reason;
    private final Instant createdAt;

    private Payment(UUID id, UUID orderId, long userId, long amount, PaymentStatus status, String transactionId,
                    String reason, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (amount <= 0) {
            throw new DomainValidationException("amount must be positive");
        }
        this.userId = userId;
        this.amount = amount;
        this.status = Objects.requireNonNull(status, "status");
        this.transactionId = transactionId;
        this.reason = truncate(reason);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static Payment approved(UUID id, UUID orderId, long userId, long amount, String transactionId,
                                   Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.APPROVED,
                Objects.requireNonNull(transactionId, "transactionId"), null, now);
    }

    public static Payment declined(UUID id, UUID orderId, long userId, long amount, String reason, Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.DECLINED, null, reason, now);
    }

    public static Payment restore(UUID id, UUID orderId, long userId, long amount, PaymentStatus status,
                                  String transactionId, String reason, Instant createdAt) {
        return new Payment(id, orderId, userId, amount, status, transactionId, reason, createdAt);
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() > REASON_MAX_LENGTH ? trimmed.substring(0, REASON_MAX_LENGTH) : trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public long userId() {
        return userId;
    }

    public long amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    public String transactionId() {
        return transactionId;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
