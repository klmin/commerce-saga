package com.klmin.commerce.inventoryservice.stock.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void reserve_movesAvailableToReserved() {
        Stock stock = Stock.restore(1L, 10, 0, T0);

        stock.reserve(3, T0.plusSeconds(1));

        assertThat(stock.available()).isEqualTo(7);
        assertThat(stock.reserved()).isEqualTo(3);
        assertThat(stock.updatedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void canReserve_upToAvailable() {
        Stock stock = Stock.restore(1L, 2, 0, T0);

        assertThat(stock.canReserve(2)).isTrue();
        assertThat(stock.canReserve(3)).isFalse();
        assertThat(stock.canReserve(0)).isFalse();
    }

    @Test
    void reserve_moreThanAvailable_fails_andKeepsStock() {
        Stock stock = Stock.restore(1L, 2, 0, T0);

        assertThatThrownBy(() -> stock.reserve(3, T0)).isInstanceOf(IllegalStateException.class);
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.reserved()).isZero();
    }
}
