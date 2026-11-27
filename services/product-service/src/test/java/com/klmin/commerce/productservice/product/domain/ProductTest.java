package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private static final Instant T0 = Instant.parse("2026-11-25T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UserId ADMIN = UserId.of(UUID.fromString("01900000-0000-7000-8001-000000000100"));
    private static final ProductId ID = ProductId.of(UUID.fromString("01900000-0000-7000-8000-000000000001"));

    private static Product product() {
        return Product.restore(ID, "키보드", "", Money.of(39_000), true, T0, T0);
    }

    @Test
    void register_trimsNameAndDefaultsDescription() {
        Product p = Product.register(ID, "  키보드 ", null, Money.of(1_000), T0);

        assertThat(p.id()).isEqualTo(ID);
        assertThat(p.name()).isEqualTo("키보드");
        assertThat(p.description()).isEmpty();
        assertThat(p.onSale()).isTrue();
    }

    @Test
    void register_rejectsZeroPriceAndBlankName() {
        assertThatThrownBy(() -> Product.register(ID, "a", "", Money.of(0), T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Product.register(ID, " ", "", Money.of(1), T0))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Product.register(ID, "a".repeat(Product.MAX_NAME_LENGTH + 1), "",
                Money.of(1), T0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void money_rejectsOutOfRange() {
        assertThatThrownBy(() -> Money.of(-1)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Money.of(Money.MAX + 1)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void changePrice_returnsChangeWithOldAndNewPrice() {
        Product p = product();

        PriceChange change = p.changePrice(Money.of(35_000), ADMIN, T1).orElseThrow();

        assertThat(change.oldPrice()).isEqualTo(Money.of(39_000));
        assertThat(change.newPrice()).isEqualTo(Money.of(35_000));
        assertThat(change.changedBy()).isEqualTo(ADMIN);
        assertThat(change.changedAt()).isEqualTo(T1);
        assertThat(p.price()).isEqualTo(Money.of(35_000));
        assertThat(p.updatedAt()).isEqualTo(T1);
    }

    @Test
    void changePrice_samePrice_noChange() {
        Product p = product();

        assertThat(p.changePrice(Money.of(39_000), ADMIN, T1)).isEmpty();
        assertThat(p.updatedAt()).isEqualTo(T0);
    }

    @Test
    void changePrice_rejectsZero() {
        assertThatThrownBy(() -> product().changePrice(Money.of(0), ADMIN, T1))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void changeSaleStatus() {
        Product p = product();

        p.changeSaleStatus(false, T1);

        assertThat(p.onSale()).isFalse();
        assertThat(p.updatedAt()).isEqualTo(T1);
    }
}
