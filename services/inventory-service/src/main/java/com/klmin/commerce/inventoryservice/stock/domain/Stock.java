package com.klmin.commerce.inventoryservice.stock.domain;

import com.klmin.commerce.inventoryservice.common.error.InvariantViolationException;

import java.time.Instant;
import java.util.Objects;

/**
 * 상품별 재고 애그리거트.
 * available: 주문 가능한 수량, reserved: 주문에 잡혀 있는 수량.
 * 주문이 완료되면 reserved 에서 빠지고(판매 확정), 보상이면 available 로 돌아간다.
 */
public final class Stock {

    private final ProductId productId;
    private int available;
    private int reserved;
    private Instant updatedAt;

    private Stock(ProductId productId, int available, int reserved, Instant updatedAt) {
        if (available < 0 || reserved < 0) {
            throw new IllegalArgumentException("stock quantity must not be negative");
        }
        this.productId = Objects.requireNonNull(productId, "productId");
        this.available = available;
        this.reserved = reserved;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Stock restore(ProductId productId, int available, int reserved, Instant updatedAt) {
        return new Stock(productId, available, reserved, updatedAt);
    }

    public boolean canReserve(Quantity quantity) {
        return quantity.value() <= available;
    }

    /** available 에서 reserved 로 옮긴다. 먼저 {@link #canReserve} 로 확인해야 한다 */
    public void reserve(Quantity quantity, Instant now) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("cannot reserve " + quantity.value() + " of product " + productId.value()
                    + " (available=" + available + ")");
        }
        available -= quantity.value();
        reserved = Math.addExact(reserved, quantity.value());
        updatedAt = now;
    }

    /** 보상: reserved 에서 available 로 되돌린다 */
    public void release(Quantity quantity, Instant now) {
        requireReserved(quantity);
        reserved -= quantity.value();
        available = Math.addExact(available, quantity.value());
        updatedAt = now;
    }

    /** 판매 확정: reserved 에서 뺀다 (available 은 예약 때 이미 줄었다) */
    public void confirm(Quantity quantity, Instant now) {
        requireReserved(quantity);
        reserved -= quantity.value();
        updatedAt = now;
    }

    // 예약 기록과 재고가 어긋난 경우. 조용히 음수로 만들지 않고 실패시켜 드러낸다
    private void requireReserved(Quantity quantity) {
        if (quantity.value() > reserved) {
            throw new InvariantViolationException("reserved stock mismatch for product " + productId.value() + ": "
                    + quantity.value() + " > reserved " + reserved);
        }
    }

    public ProductId productId() {
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
