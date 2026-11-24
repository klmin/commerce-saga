package com.klmin.commerce.paymentservice.payment.domain;

import com.klmin.commerce.paymentservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 하나의 결제 애그리거트 (orderId 당 하나).
 * <pre>
 * PG 승인       -> APPROVED
 * PG 거절       -> DECLINED
 * PG 타임아웃   -> UNKNOWN --(PG 조회)--> APPROVED | DECLINED
 * 결제 전 취소   -> CANCELLED
 * 취소 요청 + 승인(바로 또는 늦게) -> (PG 환불) -> REFUNDED
 * </pre>
 * 취소 요청({@link #requestCancel})은 상태와 따로 기록한다. UNKNOWN 에서 취소 요청을 받고 나중에 승인이 확인되면
 * 응답하지 않고 환불한다 (늦은 승인 자동 환불, ADR 0007).
 */
public final class Payment {

    public static final int REASON_MAX_LENGTH = 200;

    private final UUID id;
    private final UUID orderId;
    private final long userId;
    private final Money amount;
    private final Instant createdAt;
    private PaymentStatus status;
    private String transactionId;
    private String reason;
    private boolean cancelRequested;
    private Instant updatedAt;

    private Payment(UUID id, UUID orderId, long userId, Money amount, PaymentStatus status, String transactionId,
                    String reason, boolean cancelRequested, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        if (!Objects.requireNonNull(amount, "amount").isPositive()) {
            throw new DomainValidationException("amount must be positive");
        }
        this.userId = userId;
        this.amount = amount;
        this.status = Objects.requireNonNull(status, "status");
        this.transactionId = transactionId;
        this.reason = truncate(reason);
        this.cancelRequested = cancelRequested;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Payment approved(UUID id, UUID orderId, long userId, Money amount, String transactionId,
                                   Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.APPROVED,
                Objects.requireNonNull(transactionId, "transactionId"), null, false, now, now);
    }

    public static Payment declined(UUID id, UUID orderId, long userId, Money amount, String reason, Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.DECLINED, null, reason, false, now, now);
    }

    /** PG 응답 타임아웃. 결과는 PG 조회로 확정한다 */
    public static Payment unknown(UUID id, UUID orderId, long userId, Money amount, Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.UNKNOWN, null, "PG response timeout", false,
                now, now);
    }

    /** 결제 기록이 없는데 취소가 옴 (Pay 가 아직 안 왔거나 DLT 로 감). 늦게 온 Pay 가 결제하지 않게 남긴다 */
    public static Payment cancelledBeforePayment(UUID id, UUID orderId, long userId, Money amount, Instant now) {
        return new Payment(id, orderId, userId, amount, PaymentStatus.CANCELLED, null, "cancelled before payment",
                true, now, now);
    }

    public static Payment restore(UUID id, UUID orderId, long userId, Money amount, PaymentStatus status,
                                  String transactionId, String reason, boolean cancelRequested, Instant createdAt,
                                  Instant updatedAt) {
        return new Payment(id, orderId, userId, amount, status, transactionId, reason, cancelRequested, createdAt,
                updatedAt);
    }

    /**
     * PG 조회로 UNKNOWN 을 확정한다.
     *
     * @return 바뀌었으면 true. 이미 확정됐으면 false
     */
    public boolean resolve(boolean approved, String transactionId, String reason, Instant now) {
        if (status != PaymentStatus.UNKNOWN) {
            return false;
        }
        if (approved) {
            status = PaymentStatus.APPROVED;
            this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
            this.reason = null;
        } else {
            status = PaymentStatus.DECLINED;
            this.reason = truncate(reason);
        }
        updatedAt = now;
        return true;
    }

    /**
     * 주문 쪽 취소 요청을 기록한다. 승인됐거나(지금 또는 나중에) 하면 환불 대상이 된다({@link #needsRefund}).
     *
     * @return 처음 요청이면 true
     */
    public boolean requestCancel(Instant now) {
        if (cancelRequested) {
            return false;
        }
        cancelRequested = true;
        updatedAt = now;
        return true;
    }

    /** 취소 요청을 받았는데 승인 상태: PG 환불이 필요하다 */
    public boolean needsRefund() {
        return cancelRequested && status == PaymentStatus.APPROVED;
    }

    /** PG 환불이 끝났다. @return 바뀌었으면 true */
    public boolean refunded(Instant now) {
        if (!needsRefund()) {
            return false;
        }
        status = PaymentStatus.REFUNDED;
        reason = "refunded: order cancelled";
        updatedAt = now;
        return true;
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

    public Money amount() {
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

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
