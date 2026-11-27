package com.klmin.commerce.inventoryservice.stock.domain;

import com.klmin.commerce.inventoryservice.common.error.ConflictException;
import com.klmin.commerce.inventoryservice.common.error.InvariantViolationException;

import java.time.Instant;
import java.util.Objects;

/**
 * 상품별 재고 애그리거트.
 * available: 주문 가능한 수량, reserved: 주문에 잡혀 있는 수량.
 * 주문이 완료되면 reserved 에서 빠지고(판매 확정), 보상이면 available 로 돌아간다.
 */
public final class Stock {

    /** 관리자 입고로 만들 수 있는 재고 합계(가용 + 예약) 상한. 보상으로 예약분이 가용에 돌아와도 int 에 들어가게 */
    public static final int MAX_TOTAL = 1_000_000_000;

    private final ProductId productId;
    private long available;
    private long reserved;
    private Instant updatedAt;

    private Stock(ProductId productId, long available, long reserved, Instant updatedAt) {
        if (available < 0 || reserved < 0) {
            throw new IllegalArgumentException("stock quantity must not be negative");
        }
        this.productId = Objects.requireNonNull(productId, "productId");
        this.available = available;
        this.reserved = reserved;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static Stock restore(ProductId productId, long available, long reserved, Instant updatedAt) {
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

    /**
     * 완료 후 취소로 판매 확정된 수량을 가용으로 다시 들인다 (ADR 0020). 보상이라 거절하지 않는다.
     * 반환은 판매 확정분(입고 상한에 세지 않는 수량)이 쌓여 들어오므로 수량은 long(DB bigint, V7)이다: 실제로 넘칠 수 없다
     */
    public void restock(Quantity quantity, Instant now) {
        available = Math.addExact(available, quantity.value());
        updatedAt = now;
    }

    /**
     * 관리자 조정 (ADR 0019): 입고(+)·차감(-)을 가용 재고에 더한다. 예약 중인 수량(reserved)은 건드리지 않는다.
     * 수량을 덮어쓰지 않고 더하므로, 같은 행을 잠근 예약과 순서대로 반영돼 서로의 변경을 지우지 않는다.
     *
     * @throws ConflictException 가용 재고보다 많이 차감하려 할 때, 예약분까지 합한 재고가 {@link #MAX_TOTAL} 을 넘을 때
     */
    public void adjust(int delta, Instant now) {
        if (delta == 0) {
            throw new IllegalArgumentException("delta must not be zero");
        }
        long after = available + delta;
        if (after < 0) {
            throw new ConflictException("가용 재고(" + available + "개)보다 많이 차감할 수 없습니다.");
        }
        // 관리자가 한 번에 넣을 수 있는 규모를 정한다(입력 실수 방지). 저장은 long 이라 보상(해제·반환)이 넘치지 않는다
        if (after + reserved > MAX_TOTAL) {
            throw new ConflictException("재고는 예약분까지 합해 " + MAX_TOTAL + "개까지입니다.");
        }
        available = after;
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

    public long available() {
        return available;
    }

    public long reserved() {
        return reserved;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
