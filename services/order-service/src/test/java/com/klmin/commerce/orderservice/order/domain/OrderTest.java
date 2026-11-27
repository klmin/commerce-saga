package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final UserId USER = UserId.of(uid(1));

    private static Order pending(long pointAmount) {
        return Order.place(UUID.randomUUID(), USER,
                List.of(OrderLine.of(pid(10), 2, 1_000L), OrderLine.of(pid(11), 1, 500L)), Points.of(pointAmount), T0);
    }

    @Test
    void place_computesTotalAndPaidAmount() {
        Order order = pending(1_000L);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualTo(Money.of(2_500L));
        assertThat(order.pointAmount()).isEqualTo(Points.of(1_000L));
        assertThat(order.paidAmount()).isEqualTo(Money.of(1_500L));
    }

    @Test
    void place_pointAmountMustBeWithinTotal() {
        assertThatThrownBy(() -> pending(2_501L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> pending(-1L)).isInstanceOf(DomainValidationException.class);
        assertThat(pending(2_500L).paidAmount()).isEqualTo(Money.ZERO);
    }

    @Test
    void place_rejectsInvalidLines() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Order.place(id, USER, List.of(), Points.ZERO, T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.place(id, USER,
                List.of(OrderLine.of(pid(10), 1, 100L), OrderLine.of(pid(10), 2, 100L)), Points.ZERO, T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Order.place(id, USER,
                Collections.nCopies(Order.MAX_LINES + 1, OrderLine.of(pid(10), 1, 100L)), Points.ZERO, T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void orderLine_rejectsInvalidValues() {
        assertThatThrownBy(() -> OrderLine.of(null, 1, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> OrderLine.of(pid(1), 0, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> OrderLine.of(pid(1), OrderLine.MAX_QUANTITY + 1, 100L))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> OrderLine.of(pid(1), 1, -1L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> OrderLine.of(pid(1), 2, Long.MAX_VALUE)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void orderLine_amountIsUnitPriceTimesQuantity() {
        assertThat(OrderLine.of(pid(1), 3, 1_500L).amount()).isEqualTo(Money.of(4_500L));
    }

    @Test
    void maximumOrder_totalFitsInLong() {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 1; i <= Order.MAX_LINES; i++) {
            lines.add(OrderLine.of(pid(i), OrderLine.MAX_QUANTITY, OrderLine.MAX_UNIT_PRICE));
        }

        Order order = Order.place(UUID.randomUUID(), USER, lines, Points.ZERO, T0);

        assertThat(order.totalAmount().value())
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

        assertThat(order.isOwnedBy(UserId.of(uid(1)))).isTrue();
        assertThat(order.isOwnedBy(UserId.of(uid(2)))).isFalse();
    }
}
