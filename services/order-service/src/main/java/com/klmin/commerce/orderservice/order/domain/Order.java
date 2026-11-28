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
    private final UserId userId;
    private final List<OrderLine> lines;
    private final Money totalAmount;
    private final Points pointAmount;
    private final Instant createdAt;
    private OrderStatus status;
    private String cancelReason;
    private Instant cancelRequestedAt;
    private Instant updatedAt;

    private Order(UUID id, UserId userId, List<OrderLine> lines, Points pointAmount, OrderStatus status,
                  String cancelReason, Instant cancelRequestedAt, Instant createdAt, Instant updatedAt) {
        this.cancelRequestedAt = cancelRequestedAt;
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.lines = validateLines(lines);
        this.totalAmount = this.lines.stream().map(OrderLine::amount).reduce(Money.ZERO, Money::plus);
        Objects.requireNonNull(pointAmount, "pointAmount");
        if (pointAmount.toMoney().isGreaterThan(totalAmount)) {
            throw new DomainValidationException(
                    "pointAmount must be between 0 and totalAmount(" + totalAmount.value() + ")");
        }
        this.pointAmount = pointAmount;
        this.status = Objects.requireNonNull(status, "status");
        this.cancelReason = cancelReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Order place(UUID id, UserId userId, List<OrderLine> lines, Points pointAmount, Instant now) {
        return new Order(id, userId, lines, pointAmount, OrderStatus.PENDING, null, null, now, now);
    }

    public static Order restore(UUID id, UserId userId, List<OrderLine> lines, Points pointAmount, OrderStatus status,
                                String cancelReason, Instant cancelRequestedAt, Instant createdAt, Instant updatedAt) {
        return new Order(id, userId, lines, pointAmount, status, cancelReason, cancelRequestedAt, createdAt,
                updatedAt);
    }

    /** 취소 요청이 없는 주문 (취소 도입 전 테스트) */
    public static Order restore(UUID id, UserId userId, List<OrderLine> lines, Points pointAmount, OrderStatus status,
                                String cancelReason, Instant createdAt, Instant updatedAt) {
        return restore(id, userId, lines, pointAmount, status, cancelReason, null, createdAt, updatedAt);
    }

    /**
     * 사용자 취소 요청을 기록한다 (ADR 0020). 실제 취소는 Saga 보상이 끝나면 {@link #cancel} 이 한다.
     *
     * @return 새로 기록했으면 true, 이미 요청했거나 취소된 주문이면 false
     */
    public boolean requestCancel(Instant now) {
        if (status == OrderStatus.CANCELLED || cancelRequestedAt != null) {
            return false;
        }
        cancelRequestedAt = now;
        updatedAt = now;
        return true;
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

    /**
     * 완료된 주문의 사용자 취소가 끝났을 때 (ADR 0020). 취소를 요청한 완료 주문만 바뀐다: 늦게 온 보상 결과 등으로
     * 완료된 주문이 취소되지 않게 {@link #cancel} 과 나눈다.
     *
     * @return 상태가 바뀌었으면 true
     */
    public boolean cancelAfterCompletion(String reason, Instant now) {
        if (status != OrderStatus.COMPLETED || cancelRequestedAt == null) {
            return false;
        }
        status = OrderStatus.CANCELLED;
        cancelReason = truncate(reason);
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

    public boolean isOwnedBy(UserId userId) {
        return this.userId.equals(userId);
    }

    /** 결제할 금액 (합계 - 사용 포인트) */
    public Money paidAmount() {
        return totalAmount.minus(pointAmount.toMoney());
    }

    private static List<OrderLine> validateLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new DomainValidationException("order must have at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new DomainValidationException("order must have at most " + MAX_LINES + " lines");
        }
        Set<ProductId> productIds = new HashSet<>();
        for (OrderLine line : lines) {
            if (!productIds.add(line.productId())) {
                throw new DomainValidationException("duplicate productId: " + line.productId().value());
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

    public UserId userId() {
        return userId;
    }

    public List<OrderLine> lines() {
        return lines;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public Points pointAmount() {
        return pointAmount;
    }

    public OrderStatus status() {
        return status;
    }

    public String cancelReason() {
        return cancelReason;
    }

    /** 사용자가 취소를 요청한 시각 (없으면 null). 취소가 끝나도 남는다 */
    public Instant cancelRequestedAt() {
        return cancelRequestedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
