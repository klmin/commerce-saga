package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private static Order pending(long pointAmount) {
        return Order.place(UUID.randomUUID(), 1L,
                List.of(new OrderLine(10L, 2, 1_000L), new OrderLine(11L, 1, 500L)), pointAmount, T0);
    }

    @Test
    void place_computesTotalAndPaidAmount() {
        Order order = pending(1_000L);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualTo(2_500L);
        assertThat(order.pointAmount()).isEqualTo(1_000L);
        assertThat(order.paidAmount()).isEqualTo(1_500L);
    }

    @Test
    void place_pointAmountMustBeWithinTotal() {
        assertThatThrownBy(() -> pending(2_501L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(DomainValidationException.class);
        assertThat(pending(2_500L).paidAmount()).isZero();
    }

    @Test
    void place_rejectsInvalidLines() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Order.place(id, 1L, List.of(), 0L, T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.place(id, 1L,
                List.of(new OrderLine(10L, 1, 100L), new OrderLine(10L, 2, 100L)), 0L, T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Order.place(id, 1L,
                Collections.nCopies(Order.MAX_LINES + 1, new OrderLine(10L, 1, 100L)), 0L, T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.place(id, 0L, List.of(new OrderLine(10L, 1, 100L)), 0L, T0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void orderLine_rejectsInvalidValues() {
        assertThatThrownBy(() -> new OrderLine(0L, 1, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, 0, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, OrderLine.MAX_QUANTITY + 1, 100L))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, 1, -1L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, 2, Long.MAX_VALUE)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void maximumOrder_totalFitsInLong() {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= Order.MAX_LINES; i++) {
            lines.add(new OrderLine(i, OrderLine.MAX_QUANTITY, OrderLine.MAX_UNIT_PRICE));
        }

        Order order = Order.place(UUID.randomUUID(), 1L, lines, 0L, T0);

        assertThat(order.totalAmount())
                .isEqualTo((long) Order.MAX_LINES * OrderLine.MAX_QUANTITY * OrderLine.MAX_UNIT_PRICE);
    }

    @Test
    void complete_and_cancel_onlyFromPending() {
        Order completed = pending(0L);
        assertThat(completed.complete(T1)).isTrue();
        assertThat(completed.cancel("late", T1)).isFalse();
        assertThat(completed.status()).isEqualTo(OrderStatus.COMPLETED);

        Order cancelled = pending(0L);
        assertThat(cancelled.cancel("  payment declined  ", T1)).isTrue();
        assertThat(cancelled.cancelReason()).isEqualTo("payment declined");
        assertThat(cancelled.complete(T1)).isFalse();
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void isOwnedBy() {
        Order order = pending(0L);

        assertThat(order.isOwnedBy(1L)).isTrue();
        assertThat(order.isOwnedBy(2L)).isFalse();
    }
}
