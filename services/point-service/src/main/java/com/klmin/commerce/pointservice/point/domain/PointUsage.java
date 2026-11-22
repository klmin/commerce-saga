package com.klmin.commerce.pointservice.point.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문 하나의 포인트 사용 기록 애그리거트 (orderId 당 하나).
 * <ul>
 *   <li>같은 주문의 UsePoint 가 다시 오면 이 기록으로 응답하고 다시 차감하지 않는다</li>
 *   <li>RefundPoint(보상)는 USED 일 때만 이 기록의 금액만큼 돌려준다</li>
 * </ul>
 * 전이: USED -> REFUNDED. REJECTED, REFUNDED 는 끝 상태.
 */
public final class PointUsage {

    public static final int REASON_MAX_LENGTH = 200;

    private final UUID orderId;
    private final Long userId;
    private final long amount;
    private final String reason;
    private final Instant createdAt;
    private PointUsageStatus status;
    private Instant updatedAt;

    private PointUsage(UUID orderId, Long userId, long amount, PointUsageStatus status, String reason,
                       Instant createdAt, Instant updatedAt) {
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.userId = userId;
        this.amount = amount;
        this.status = Objects.requireNonNull(status, "status");
        this.reason = truncate(reason);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static PointUsage used(UUID orderId, long userId, long amount, Instant now) {
        return new PointUsage(orderId, userId, amount, PointUsageStatus.USED, null, now, now);
    }

    public static PointUsage rejected(UUID orderId, long userId, long amount, String reason, Instant now) {
        return new PointUsage(orderId, userId, amount, PointUsageStatus.REJECTED, reason, now, now);
    }

    /** 사용 기록 없이 RefundPoint 가 먼저 온 경우. 늦게 온 UsePoint 가 차감하지 못하게 막는다 */
    public static PointUsage refundedWithoutUsage(UUID orderId, Instant now) {
        return new PointUsage(orderId, null, 0L, PointUsageStatus.REFUNDED, "refunded before usage", now, now);
    }

    public static PointUsage restore(UUID orderId, Long userId, long amount, PointUsageStatus status, String reason,
                                     Instant createdAt, Instant updatedAt) {
        return new PointUsage(orderId, userId, amount, status, reason, createdAt, updatedAt);
    }

    /** 보상. @return 실제로 돌려줘야 하면 true (USED 에서만) */
    public boolean refund(Instant now) {
        if (status != PointUsageStatus.USED) {
            return false;
        }
        status = PointUsageStatus.REFUNDED;
        updatedAt = now;
        return true;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() > REASON_MAX_LENGTH ? trimmed.substring(0, REASON_MAX_LENGTH) : trimmed;
    }

    public UUID orderId() {
        return orderId;
    }

    public Long userId() {
        return userId;
    }

    public long amount() {
        return amount;
    }

    public PointUsageStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
