package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectTest {

    @Test
    void money_isNeverNegative() {
        assertThatThrownBy(() -> Money.of(-1L)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of(100L).minus(Money.of(101L))).isInstanceOf(DomainValidationException.class);
        assertThat(Money.of(100L).minus(Money.of(100L))).isEqualTo(Money.ZERO);
    }

    @Test
    void money_arithmetic() {
        assertThat(Money.of(100L).plus(Money.of(50L))).isEqualTo(Money.of(150L));
        assertThat(Money.of(1_000L).times(Quantity.of(3))).isEqualTo(Money.of(3_000L));
        assertThat(Money.of(2L).isGreaterThan(Money.of(1L))).isTrue();
        assertThat(Money.ZERO.isPositive()).isFalse();
    }

    @Test
    void money_overflowFails() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).plus(Money.of(1L))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE).times(Quantity.of(2))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void points_convertToMoneyOneToOne() {
        assertThat(Points.of(1_500L).toMoney()).isEqualTo(Money.of(1_500L));
        assertThatThrownBy(() -> Points.of(-1L)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void quantityMustBePositive_userIdAndProductIdNotNull() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> ProductId.of(null)).isInstanceOf(DomainValidationException.class);
        assertThat(UserId.of(uid(7))).isEqualTo(UserId.of(UUID.fromString("01900000-0000-7000-8001-000000000007")));
    }
}
