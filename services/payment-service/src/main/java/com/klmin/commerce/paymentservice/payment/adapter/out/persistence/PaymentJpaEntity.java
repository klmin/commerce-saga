package com.klmin.commerce.paymentservice.payment.adapter.out.persistence;

import com.klmin.commerce.paymentservice.payment.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(length = 200)
    private String reason;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentJpaEntity() {
    }

    PaymentJpaEntity(UUID id, UUID orderId, long userId, long amount, PaymentStatus status, String transactionId,
                     String reason, boolean cancelRequested, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.transactionId = transactionId;
        this.reason = reason;
        this.cancelRequested = cancelRequested;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void change(PaymentStatus status, String transactionId, String reason, boolean cancelRequested, Instant updatedAt) {
        this.status = status;
        this.transactionId = transactionId;
        this.reason = reason;
        this.cancelRequested = cancelRequested;
        this.updatedAt = updatedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    long getUserId() {
        return userId;
    }

    long getAmount() {
        return amount;
    }

    PaymentStatus getStatus() {
        return status;
    }

    String getTransactionId() {
        return transactionId;
    }

    String getReason() {
        return reason;
    }

    boolean isCancelRequested() {
        return cancelRequested;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
