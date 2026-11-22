package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 주문 애그리거트 루트. Order + OrderLine 이 한 일관성 경계다.
 * <p>
 * 결제 금액 = 합계 - 사용 포인트. 상태 전이는 PENDING 에서만 일어나고 결과(COMPLETED, CANCELLED)는 되돌아가지 않는다.
 */
public final class Order {

    public static final int MAX_LINES = 50;
    public static final int CANCEL_REASON_MAX_LENGTH = 200;

    private final UUID id;
    private final long userId;
    private final List<OrderLine> lines;
    private final long totalAmount;
    private final long pointAmount;
    private final Instant createdAt;
    private OrderStatus status;
    private String cancelReason;
    private Instant updatedAt;

    private Order(UUID id, long userId, List<OrderLine> lines, long pointAmount, OrderStatus status,
                  String cancelReason, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        if (userId <= 0) {
            throw new DomainValidationException("userId must be positive");
        }
        this.userId = userId;
        this.lines = validateLines(lines);
        this.totalAmount = this.lines.stream().mapToLong(OrderLine::amount).reduce(0L, Math::addExact);
        if (pointAmount < 0 || pointAmount > totalAmount) {
            throw new DomainValidationException("pointAmount must be between 0 and totalAmount(" + totalAmount + ")");
        }
        this.pointAmount = pointAmount;
        this.status = Objects.requireNonNull(status, "status");
        this.cancelReason = cancelReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Order place(UUID id, long userId, List<OrderLine> lines, long pointAmount, Instant now) {
        return new Order(id, userId, lines, pointAmount, OrderStatus.PENDING, null, now, now);
    }

    public static Order restore(UUID id, long userId, List<OrderLine> lines, long pointAmount, OrderStatus status,
                                String cancelReason, Instant createdAt, Instant updatedAt) {
        return new Order(id, userId, lines, pointAmount, status, cancelReason, createdAt, updatedAt);
    }

    /** @return 상태가 바뀌었으면 true, 이미 결론이 난 주문이면 false */
    public boolean complete(Instant now) {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        status = OrderStatus.COMPLETED;
        updatedAt = now;
        return true;
    }

    /** @return 상태가 바뀌었으면 true, 이미 결론이 난 주문이면 false */
    public boolean cancel(String reason, Instant now) {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        cancelReason = truncate(reason);
        updatedAt = now;
        return true;
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
    }

    /** 결제할 금액 (합계 - 사용 포인트) */
    public long paidAmount() {
        return totalAmount - pointAmount;
    }

    private static List<OrderLine> validateLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new DomainValidationException("order must have at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new DomainValidationException("order must have at most " + MAX_LINES + " lines");
        }
        Set<Long> productIds = new HashSet<>();
        for (OrderLine line : lines) {
            if (!productIds.add(line.productId())) {
                throw new DomainValidationException("duplicate productId: " + line.productId());
            }
        }
        return List.copyOf(lines);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() > CANCEL_REASON_MAX_LENGTH ? trimmed.substring(0, CANCEL_REASON_MAX_LENGTH) : trimmed;
    }

    public UUID id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public long totalAmount() {
        return totalAmount;
    }

    public long pointAmount() {
        return pointAmount;
    }

    public OrderStatus status() {
        return status;
    }

    public String cancelReason() {
        return cancelReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
