package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.IdempotencyKeyReusedException;
import com.klmin.commerce.orderservice.order.application.OrderPriceChangedException;
import com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException;
import com.klmin.commerce.orderservice.order.application.ProductNotOrderableException;
import com.klmin.commerce.orderservice.common.error.DomainValidationException;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.out.ProductCatalogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOrderServiceTest {

    private final OrderServiceTest.InMemoryOrders orders = new OrderServiceTest.InMemoryOrders();
    private final OrderServiceTest.InMemorySagas sagas = new OrderServiceTest.InMemorySagas();
    private final OrderServiceTest.Recording sent = new OrderServiceTest.Recording();
    private final InMemoryIdempotencyKeys keys = new InMemoryIdempotencyKeys();
    private final FakeCatalog catalog = new FakeCatalog();
    private final PlaceOrderService service = new PlaceOrderService(
            new OrderService(orders, orders, sagas, sent, sent, new InMemoryProcessedMessages(), keys,
                    Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC)),
            catalog);

    @BeforeEach
    void prices() {
        catalog.products.put(10L, new ProductCatalogPort.CatalogProduct(10L, 1_000L, true));
        catalog.products.put(11L, new ProductCatalogPort.CatalogProduct(11L, 500L, true));
    }

    private static PlaceOrderCommand command(String key, long price10) {
        return new PlaceOrderCommand(1L, List.of(
                new PlaceOrderCommand.Line(10L, 2, price10),
                new PlaceOrderCommand.Line(11L, 1, 500L)), 0L, key);
    }

    @Test
    void samePrice_placesOrderWithSnapshot() {
        PlaceOrderResult result = service.placeOrder(command(null, 1_000L));

        assertThat(result.created()).isTrue();
        assertThat(result.order().totalAmount().value()).isEqualTo(2_500L);
        assertThat(sent.log).hasSize(1);
    }

    @Test
    void priceChanged_409_withCurrentPrices_andNoOrder() {
        catalog.products.put(10L, new ProductCatalogPort.CatalogProduct(10L, 1_200L, true));

        assertThatThrownBy(() -> service.placeOrder(command("k1", 1_000L)))
                .isInstanceOfSatisfying(OrderPriceChangedException.class, e -> assertThat(e.currentPrices())
                        .containsExactly(new OrderPriceChangedException.CurrentPrice(10L, 1_200L),
                                new OrderPriceChangedException.CurrentPrice(11L, 500L)));
        assertThat(orders.store).isEmpty();
        assertThat(sent.log).isEmpty();
        // 409 는 키를 남기지 않는다 → 같은 키로 새 가격을 보내면 주문된다
        assertThat(keys.store).isEmpty();
        assertThat(service.placeOrder(command("k1", 1_200L)).created()).isTrue();
    }

    @Test
    void priceDropped_alsoConflicts() {
        catalog.products.put(10L, new ProductCatalogPort.CatalogProduct(10L, 900L, true));

        assertThatThrownBy(() -> service.placeOrder(command(null, 1_000L)))
                .isInstanceOf(OrderPriceChangedException.class);
    }

    @Test
    void unknownOrStoppedProduct_422() {
        catalog.products.remove(11L);
        assertThatThrownBy(() -> service.placeOrder(command(null, 1_000L)))
                .isInstanceOf(ProductNotOrderableException.class).hasMessageContaining("11");

        catalog.products.put(11L, new ProductCatalogPort.CatalogProduct(11L, 500L, false));
        assertThatThrownBy(() -> service.placeOrder(command(null, 1_000L)))
                .isInstanceOf(ProductNotOrderableException.class);
        assertThat(orders.store).isEmpty();
    }

    @Test
    void catalogDown_503_andNoOrder() {
        catalog.down = true;

        assertThatThrownBy(() -> service.placeOrder(command("k2", 1_000L)))
                .isInstanceOf(ProductCatalogUnavailableException.class);
        assertThat(orders.store).isEmpty();
        assertThat(keys.store).isEmpty();
    }

    @Test
    void invalidLine_400_withoutCallingCatalog() {
        catalog.down = true;

        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderCommand(1L,
                List.of(new PlaceOrderCommand.Line(10L, 0, 1_000L)), 0L, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void replay_returnsFirstOrder_evenIfPriceChangedOrCatalogDown() {
        PlaceOrderResult first = service.placeOrder(command("k3", 1_000L));
        catalog.products.put(10L, new ProductCatalogPort.CatalogProduct(10L, 2_000L, true));
        catalog.down = true;

        PlaceOrderResult again = service.placeOrder(command("k3", 1_000L));

        assertThat(again.created()).isFalse();
        assertThat(again.order().id()).isEqualTo(first.order().id());
        assertThatThrownBy(() -> service.placeOrder(command("k3", 2_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    static final class FakeCatalog implements ProductCatalogPort {

        final Map<Long, CatalogProduct> products = new HashMap<>();
        boolean down;

        @Override
        public List<CatalogProduct> findByIds(Collection<Long> productIds) {
            if (down) {
                throw new ProductCatalogUnavailableException(new RuntimeException("down"));
            }
            return productIds.stream().map(products::get).filter(Objects::nonNull).toList();
        }
    }
}
