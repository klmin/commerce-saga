package com.klmin.commerce.inventoryservice.stock.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 상품별 재고 애그리거트.
 * available: 주문 가능한 수량, reserved: 주문에 잡혀 있는 수량 (결제 완료 후 확정, 실패 시 해제는 2단계)
 */
public final class Stock {

    private final long productId;
    private int available;
    private int reserved;
    private Instant updatedAt;

    private Stock(long productId, int available, int reserved, Instant updatedAt) {
        if (available < 0 || reserved < 0) {
            throw new IllegalArgumentException("stock quantity must not be negative");
        }
        this.productId = productId;
        this.available = available;
        this.reserved = reserved;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Stock restore(long productId, int available, int reserved, Instant updatedAt) {
        return new Stock(productId, available, reserved, updatedAt);
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && quantity <= available;
    }

    /** available 에서 reserved 로 옮긴다. 먼저 {@link #canReserve} 로 확인해야 한다 */
    public void reserve(int quantity, Instant now) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "cannot reserve " + quantity + " of product " + productId + " (available=" + available + ")");
        }
        available -= quantity;
        reserved = Math.addExact(reserved, quantity);
        updatedAt = now;
    }

    public long productId() {
        return productId;
    }

    public int available() {
        return available;
    }

    public int reserved() {
        return reserved;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
