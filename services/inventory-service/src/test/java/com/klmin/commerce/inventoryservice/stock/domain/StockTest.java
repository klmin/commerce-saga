package com.klmin.commerce.inventoryservice.stock.domain;

import com.klmin.commerce.inventoryservice.common.error.InvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void reserve_movesAvailableToReserved() {
        Stock stock = Stock.restore(ProductId.of(1L), 10, 0, T0);

        stock.reserve(Quantity.of(3), T0.plusSeconds(1));

        assertThat(stock.available()).isEqualTo(7);
        assertThat(stock.reserved()).isEqualTo(3);
        assertThat(stock.updatedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void canReserve_upToAvailable() {
        Stock stock = Stock.restore(ProductId.of(1L), 2, 0, T0);

        assertThat(stock.canReserve(Quantity.of(2))).isTrue();
        assertThat(stock.canReserve(Quantity.of(3))).isFalse();
    }

    @Test
    void release_and_confirm() {
        Stock stock = Stock.restore(ProductId.of(1L), 7, 3, T0);

        stock.release(Quantity.of(1), T0);
        assertThat(stock.available()).isEqualTo(8);
        assertThat(stock.reserved()).isEqualTo(2);

        stock.confirm(Quantity.of(2), T0);
        assertThat(stock.available()).isEqualTo(8);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void release_or_confirm_moreThanReserved_isInvariantViolation() {
        Stock stock = Stock.restore(ProductId.of(1L), 7, 1, T0);

        assertThatThrownBy(() -> stock.release(Quantity.of(2), T0)).isInstanceOf(InvariantViolationException.class);
        assertThatThrownBy(() -> stock.confirm(Quantity.of(2), T0)).isInstanceOf(InvariantViolationException.class);
        assertThat(stock.reserved()).isEqualTo(1);
    }

    @Test
    void reserve_moreThanAvailable_fails_andKeepsStock() {
        Stock stock = Stock.restore(ProductId.of(1L), 2, 0, T0);

        assertThatThrownBy(() -> stock.reserve(Quantity.of(3), T0)).isInstanceOf(IllegalStateException.class);
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void quantityAndProductId_mustBePositive() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductId.of(0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
