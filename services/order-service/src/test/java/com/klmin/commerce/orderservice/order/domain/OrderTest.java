package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private static Order pending() {
        return Order.place(UUID.randomUUID(), 1L,
                List.of(new OrderLine(10L, 2, 1_000L), new OrderLine(11L, 1, 500L)), T0);
    }

    @Test
    void place_computesTotalAndStartsPending() {
        Order order = pending();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualTo(2_500L);
        assertThat(order.createdAt()).isEqualTo(T0);
    }

    @Test
    void place_rejectsInvalidLines() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> Order.place(id, 1L, List.of(), T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.place(id, 1L,
                List.of(new OrderLine(10L, 1, 100L), new OrderLine(10L, 2, 100L)), T0))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Order.place(id, 1L,
                Collections.nCopies(Order.MAX_LINES + 1, new OrderLine(10L, 1, 100L)), T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Order.place(id, 0L, List.of(new OrderLine(10L, 1, 100L)), T0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void orderLine_rejectsInvalidValues() {
        assertThatThrownBy(() -> new OrderLine(0L, 1, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, 0, 100L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, OrderLine.MAX_QUANTITY + 1, 100L))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderLine(1L, 1, -1L)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void confirm_onlyFromPending() {
        Order order = pending();

        assertThat(order.confirm(T1)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.updatedAt()).isEqualTo(T1);

        // 중복 응답
        assertThat(order.confirm(T1.plusSeconds(1))).isFalse();
        // 늦게 도착한 반대 응답으로 상태가 되돌아가지 않는다
        assertThat(order.reject("late", T1.plusSeconds(2))).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.updatedAt()).isEqualTo(T1);
    }

    @Test
    void reject_onlyFromPending() {
        Order order = pending();

        assertThat(order.reject("  out of stock  ", T1)).isTrue();
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.rejectReason()).isEqualTo("out of stock");

        assertThat(order.confirm(T1.plusSeconds(1))).isFalse();
        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void reject_truncatesLongReason() {
        Order order = pending();

        order.reject("x".repeat(Order.REJECT_REASON_MAX_LENGTH + 10), T1);

        assertThat(order.rejectReason()).hasSize(Order.REJECT_REASON_MAX_LENGTH);
    }

    @Test
    void isOwnedBy() {
        Order order = pending();

        assertThat(order.isOwnedBy(1L)).isTrue();
        assertThat(order.isOwnedBy(2L)).isFalse();
    }
}
