package com.klmin.commerce.inventoryservice.stock.domain;

import com.klmin.commerce.inventoryservice.common.error.ConflictException;
import com.klmin.commerce.inventoryservice.common.error.InvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");
    private static final ProductId P1 = ProductId.of(UUID.fromString("01900000-0000-7000-8000-0000000000a1"));

    @Test
    void reserve_movesAvailableToReserved() {
        Stock stock = Stock.restore(P1, 10, 0, T0);

        stock.reserve(Quantity.of(3), T0.plusSeconds(1));

        assertThat(stock.available()).isEqualTo(7);
        assertThat(stock.reserved()).isEqualTo(3);
        assertThat(stock.updatedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void canReserve_upToAvailable() {
        Stock stock = Stock.restore(P1, 2, 0, T0);

        assertThat(stock.canReserve(Quantity.of(2))).isTrue();
        assertThat(stock.canReserve(Quantity.of(3))).isFalse();
    }

    @Test
    void release_and_confirm() {
        Stock stock = Stock.restore(P1, 7, 3, T0);

        stock.release(Quantity.of(1), T0);
        assertThat(stock.available()).isEqualTo(8);
        assertThat(stock.reserved()).isEqualTo(2);

        stock.confirm(Quantity.of(2), T0);
        assertThat(stock.available()).isEqualTo(8);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void release_or_confirm_moreThanReserved_isInvariantViolation() {
        Stock stock = Stock.restore(P1, 7, 1, T0);

        assertThatThrownBy(() -> stock.release(Quantity.of(2), T0)).isInstanceOf(InvariantViolationException.class);
        assertThatThrownBy(() -> stock.confirm(Quantity.of(2), T0)).isInstanceOf(InvariantViolationException.class);
        assertThat(stock.reserved()).isEqualTo(1);
    }

    @Test
    void reserve_moreThanAvailable_fails_andKeepsStock() {
        Stock stock = Stock.restore(P1, 2, 0, T0);

        assertThatThrownBy(() -> stock.reserve(Quantity.of(3), T0)).isInstanceOf(IllegalStateException.class);
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void adjust_addsToAvailable_andKeepsReserved() {
        Stock stock = Stock.restore(P1, 5, 2, T0);

        stock.adjust(10, T0.plusSeconds(1));
        assertThat(stock.available()).isEqualTo(15);
        assertThat(stock.reserved()).isEqualTo(2);
        assertThat(stock.updatedAt()).isEqualTo(T0.plusSeconds(1));

        stock.adjust(-15, T0.plusSeconds(2));
        assertThat(stock.available()).isZero();
        assertThat(stock.reserved()).isEqualTo(2);
    }

    @Test
    void adjust_belowZero_isConflict_andKeepsStock() {
        Stock stock = Stock.restore(P1, 3, 4, T0);

        // 예약 중인 수량(reserved)은 뺄 수 없다: 가용 3 만 차감할 수 있다
        assertThatThrownBy(() -> stock.adjust(-4, T0)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("3");
        assertThat(stock.available()).isEqualTo(3);
        assertThat(stock.updatedAt()).isEqualTo(T0);
    }

    @Test
    void adjust_zeroOrOverflow_isRejected() {
        Stock stock = Stock.restore(P1, Integer.MAX_VALUE - 1, 0, T0);

        assertThatThrownBy(() -> stock.adjust(0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.adjust(2, T0)).isInstanceOf(ConflictException.class);
        assertThat(stock.available()).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void quantityMustBePositive_andProductIdNotNull() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductId.of(null)).isInstanceOf(NullPointerException.class);
    }
}
