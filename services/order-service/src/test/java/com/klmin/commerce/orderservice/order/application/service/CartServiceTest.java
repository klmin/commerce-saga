package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.ProductNotOrderableException;
import com.klmin.commerce.orderservice.order.application.port.in.CartResult;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort.CatalogProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartServiceTest {

    private final InMemoryCart cart = new InMemoryCart();
    private final PlaceOrderServiceTest.FakeCatalog catalog = new PlaceOrderServiceTest.FakeCatalog();
    private final CartService service = new CartService(cart, catalog,
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void products() {
        catalog.products.put(1L, new CatalogProduct(1L, "키보드", 39_000L, true));
        catalog.products.put(2L, new CatalogProduct(2L, "마우스", 25_000L, true));
    }

    @Test
    void put_storesCurrentPriceAsSeenPrice_andShowsPriceChangeLater() {
        service.put(7L, 1L, 2);
        catalog.products.put(1L, new CatalogProduct(1L, "키보드", 42_000L, true));

        CartResult result = service.get(7L);

        assertThat(result.pricesAvailable()).isTrue();
        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.addedPrice()).isEqualTo(39_000L);
            assertThat(i.currentPrice()).isEqualTo(42_000L);
            assertThat(i.priceChanged()).isTrue();
            assertThat(i.quantity()).isEqualTo(2);
            assertThat(i.name()).isEqualTo("키보드");
        });
    }

    @Test
    void put_unknownOrStoppedProduct_422() {
        catalog.products.put(2L, new CatalogProduct(2L, "마우스", 25_000L, false));

        assertThatThrownBy(() -> service.put(7L, 9L, 1)).isInstanceOf(ProductNotOrderableException.class);
        assertThatThrownBy(() -> service.put(7L, 2L, 1)).isInstanceOf(ProductNotOrderableException.class);
        assertThat(service.get(7L).items()).isEmpty();
    }

    @Test
    void put_catalogDown_503() {
        catalog.down = true;

        assertThatThrownBy(() -> service.put(7L, 1L, 1)).isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void get_catalogDown_fallsBackToSeenPrices() {
        service.put(7L, 1L, 1);
        catalog.down = true;

        CartResult result = service.get(7L);

        assertThat(result.pricesAvailable()).isFalse();
        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.addedPrice()).isEqualTo(39_000L);
            assertThat(i.currentPrice()).isNull();
            assertThat(i.priceChanged()).isFalse();
        });
    }

    @Test
    void get_productRemovedFromCatalog_isNotOnSale() {
        service.put(7L, 1L, 1);
        catalog.products.remove(1L);

        assertThat(service.get(7L).items()).singleElement()
                .satisfies(i -> assertThat(i.onSale()).isFalse());
    }

    @Test
    void cartsAreSeparatedByUser_andRemoveWorks() {
        service.put(7L, 1L, 1);
        service.put(8L, 2L, 1);

        assertThat(service.get(7L).items()).extracting(CartResult.Item::productId).containsExactly(1L);
        assertThat(service.remove(7L, 1L).items()).isEmpty();
        assertThat(service.get(8L).items()).hasSize(1);
    }
}
