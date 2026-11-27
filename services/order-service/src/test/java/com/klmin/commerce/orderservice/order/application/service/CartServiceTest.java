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

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartServiceTest {

    private final InMemoryCart cart = new InMemoryCart();
    private final PlaceOrderServiceTest.FakeCatalog catalog = new PlaceOrderServiceTest.FakeCatalog();
    private final CartService service = new CartService(cart, catalog,
            Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void products() {
        catalog.products.put(pid(1), new CatalogProduct(pid(1), "키보드", 39_000L, true));
        catalog.products.put(pid(2), new CatalogProduct(pid(2), "마우스", 25_000L, true));
    }

    @Test
    void put_storesCurrentPriceAsSeenPrice_andShowsPriceChangeLater() {
        service.put(uid(7), pid(1), 2);
        catalog.products.put(pid(1), new CatalogProduct(pid(1), "키보드", 42_000L, true));

        CartResult result = service.get(uid(7));

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
        catalog.products.put(pid(2), new CatalogProduct(pid(2), "마우스", 25_000L, false));

        assertThatThrownBy(() -> service.put(uid(7), pid(9), 1)).isInstanceOf(ProductNotOrderableException.class);
        assertThatThrownBy(() -> service.put(uid(7), pid(2), 1)).isInstanceOf(ProductNotOrderableException.class);
        assertThat(service.get(uid(7)).items()).isEmpty();
    }

    @Test
    void put_catalogDown_503() {
        catalog.down = true;

        assertThatThrownBy(() -> service.put(uid(7), pid(1), 1)).isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void get_catalogDown_fallsBackToSeenPrices() {
        service.put(uid(7), pid(1), 1);
        catalog.down = true;

        CartResult result = service.get(uid(7));

        assertThat(result.pricesAvailable()).isFalse();
        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.addedPrice()).isEqualTo(39_000L);
            assertThat(i.currentPrice()).isNull();
            assertThat(i.priceChanged()).isFalse();
        });
    }

    @Test
    void get_productRemovedFromCatalog_isNotOnSale() {
        service.put(uid(7), pid(1), 1);
        catalog.products.remove(pid(1));

        assertThat(service.get(uid(7)).items()).singleElement()
                .satisfies(i -> assertThat(i.onSale()).isFalse());
    }

    @Test
    void cartsAreSeparatedByUser_andRemoveWorks() {
        service.put(uid(7), pid(1), 1);
        service.put(uid(8), pid(2), 1);

        assertThat(service.get(uid(7)).items()).extracting(CartResult.Item::productId).containsExactly(pid(1));
        assertThat(service.remove(uid(7), pid(1)).items()).isEmpty();
        assertThat(service.get(uid(8)).items()).hasSize(1);
    }
}
