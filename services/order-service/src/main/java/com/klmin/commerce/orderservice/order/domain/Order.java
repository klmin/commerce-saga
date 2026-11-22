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
 * 상태 전이는 PENDING 에서만 일어난다. 이미 결론이 난 주문에 늦게·중복으로 도착한 응답은
 * 상태를 바꾸지 않고 false 를 돌려준다 (at-least-once 전달에서 상태가 되돌아가지 않게).
 */
public final class Order {

    public static final int MAX_LINES = 50;
    public static final int REJECT_REASON_MAX_LENGTH = 200;

    private final UUID id;
    private final long userId;
    private final List<OrderLine> lines;
    private final long totalAmount;
    private final Instant createdAt;
    private OrderStatus status;
    private String rejectReason;
    private Instant updatedAt;

    private Order(UUID id, long userId, List<OrderLine> lines, OrderStatus status, String rejectReason,
                  Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        if (userId <= 0) {
            throw new DomainValidationException("userId must be positive");
        }
        this.userId = userId;
        this.lines = validateLines(lines);
        this.totalAmount = lines.stream().mapToLong(OrderLine::amount).reduce(0L, Math::addExact);
        this.status = Objects.requireNonNull(status, "status");
        this.rejectReason = rejectReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Order place(UUID id, long userId, List<OrderLine> lines, Instant now) {
        return new Order(id, userId, lines, OrderStatus.PENDING, null, now, now);
    }

    public static Order restore(UUID id, long userId, List<OrderLine> lines, OrderStatus status,
                                String rejectReason, Instant createdAt, Instant updatedAt) {
        return new Order(id, userId, lines, status, rejectReason, createdAt, updatedAt);
    }

    /** 재고 예약 성공. @return 상태가 바뀌었으면 true, 이미 결론이 난 주문이면 false */
    public boolean confirm(Instant now) {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        status = OrderStatus.CONFIRMED;
        updatedAt = now;
        return true;
    }

    /** 재고 예약 실패. @return 상태가 바뀌었으면 true, 이미 결론이 난 주문이면 false */
    public boolean reject(String reason, Instant now) {
        if (status != OrderStatus.PENDING) {
            return false;
        }
        status = OrderStatus.REJECTED;
        rejectReason = truncate(reason);
        updatedAt = now;
        return true;
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
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
        return trimmed.length() > REJECT_REASON_MAX_LENGTH ? trimmed.substring(0, REJECT_REASON_MAX_LENGTH) : trimmed;
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

    public OrderStatus status() {
        return status;
    }

    public String rejectReason() {
        return rejectReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
