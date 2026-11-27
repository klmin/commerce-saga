package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");

    @Test
    void put_newItem_keepsSeenPrice_andChangingQuantityKeepsIt() {
        Cart cart = Cart.of(UserId.of(uid(1)), List.of());

        cart.put(ProductId.of(pid(1)), Quantity.of(1), Money.of(39_000), T0);
        CartItem changed = cart.put(ProductId.of(pid(1)), Quantity.of(3), Money.of(42_000), T0.plusSeconds(60));

        assertThat(changed.quantity()).isEqualTo(Quantity.of(3));
        assertThat(changed.addedPrice()).isEqualTo(Money.of(39_000));
        assertThat(changed.addedAt()).isEqualTo(T0);
        assertThat(cart.items()).hasSize(1);
    }

    @Test
    void put_rejectsOverMaxItems_butAllowsChangingExisting() {
        List<CartItem> full = new ArrayList<>();
        for (int i = 1; i <= Cart.MAX_ITEMS; i++) {
            full.add(new CartItem(ProductId.of(pid(i)), Quantity.of(1), Money.of(1), T0));
        }
        Cart cart = Cart.of(UserId.of(uid(1)), full);

        assertThatThrownBy(() -> cart.put(ProductId.of(pid(999)), Quantity.of(1), Money.of(1), T0))
                .isInstanceOf(DomainValidationException.class);
        assertThat(cart.put(ProductId.of(pid(1)), Quantity.of(2), Money.of(1), T0).quantity().value()).isEqualTo(2);
    }

    @Test
    void put_rejectsTooManyQuantity() {
        assertThatThrownBy(() -> Cart.of(UserId.of(uid(1)), List.of())
                .put(ProductId.of(pid(1)), Quantity.of(OrderLine.MAX_QUANTITY + 1), Money.of(1), T0))
                .isInstanceOf(DomainValidationException.class);
    }
}
