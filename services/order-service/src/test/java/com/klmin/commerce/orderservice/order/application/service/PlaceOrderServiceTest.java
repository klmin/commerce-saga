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
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOrderServiceTest {

    private final OrderServiceTest.InMemoryOrders orders = new OrderServiceTest.InMemoryOrders();
    private final OrderServiceTest.InMemorySagas sagas = new OrderServiceTest.InMemorySagas();
    private final OrderServiceTest.Recording sent = new OrderServiceTest.Recording();
    private final InMemoryIdempotencyKeys keys = new InMemoryIdempotencyKeys();
    private final FakeCatalog catalog = new FakeCatalog();
    private final InMemoryCart cart = new InMemoryCart();
    private final PlaceOrderService service = new PlaceOrderService(
            new OrderService(orders, orders, sagas, sent, sent, new InMemoryProcessedMessages(), keys, cart,
                    new InMemorySagaOutcomes(),
                    Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC)),
            catalog);

    @BeforeEach
    void prices() {
        catalog.products.put(pid(10), new ProductCatalogPort.CatalogProduct(pid(10), "a", 1_000L, true));
        catalog.products.put(pid(11), new ProductCatalogPort.CatalogProduct(pid(11), "b", 500L, true));
    }

    private static PlaceOrderCommand command(String key, long price10) {
        return new PlaceOrderCommand(uid(1), List.of(
                new PlaceOrderCommand.Line(pid(10), 2, price10),
                new PlaceOrderCommand.Line(pid(11), 1, 500L)), 0L, key);
    }

    @Test
    void samePrice_placesOrderWithSnapshot() {
        PlaceOrderResult result = service.placeOrder(command(null, 1_000L));

        assertThat(result.created()).isTrue();
        assertThat(result.order().totalAmount().value()).isEqualTo(2_500L);
        assertThat(sent.log).hasSize(1);
    }

    @Test
    void fromCart_removesOrderedLinesOnly() {
        java.time.Instant t = Instant.parse("2026-11-25T00:00:00Z");
        var user = com.klmin.commerce.orderservice.order.domain.UserId.of(uid(1));
        for (UUID id : new UUID[] {pid(10), pid(11), pid(12)}) {
            cart.upsert(user, new com.klmin.commerce.orderservice.order.domain.CartItem(
                    com.klmin.commerce.orderservice.order.domain.ProductId.of(id),
                    com.klmin.commerce.orderservice.order.domain.Quantity.of(1),
                    com.klmin.commerce.orderservice.order.domain.Money.of(1), t), t);
        }

        service.placeOrder(new PlaceOrderCommand(uid(1), command(null, 1_000L).lines(), 0L, null, true));

        assertThat(cart.findByUserId(user)).extracting(i -> i.productId().value()).containsExactly(pid(12));
    }

    @Test
    void notFromCart_keepsCart_andFailedOrderKeepsCart() {
        java.time.Instant t = Instant.parse("2026-11-25T00:00:00Z");
        var user = com.klmin.commerce.orderservice.order.domain.UserId.of(uid(1));
        cart.upsert(user, new com.klmin.commerce.orderservice.order.domain.CartItem(
                com.klmin.commerce.orderservice.order.domain.ProductId.of(pid(10)),
                com.klmin.commerce.orderservice.order.domain.Quantity.of(1),
                com.klmin.commerce.orderservice.order.domain.Money.of(1), t), t);

        service.placeOrder(command(null, 1_000L));
        catalog.products.put(pid(10), new ProductCatalogPort.CatalogProduct(pid(10), "a", 1_200L, true));
        assertThatThrownBy(() -> service.placeOrder(
                new PlaceOrderCommand(uid(1), command(null, 1_000L).lines(), 0L, null, true)))
                .isInstanceOf(OrderPriceChangedException.class);

        assertThat(cart.findByUserId(user)).hasSize(1);
    }

    @Test
    void priceChanged_409_withCurrentPrices_andNoOrder() {
        catalog.products.put(pid(10), new ProductCatalogPort.CatalogProduct(pid(10), "a", 1_200L, true));

        assertThatThrownBy(() -> service.placeOrder(command("k1", 1_000L)))
                .isInstanceOfSatisfying(OrderPriceChangedException.class, e -> assertThat(e.currentPrices())
                        .containsExactly(new OrderPriceChangedException.CurrentPrice(pid(10), 1_200L),
                                new OrderPriceChangedException.CurrentPrice(pid(11), 500L)));
        assertThat(orders.store).isEmpty();
        assertThat(sent.log).isEmpty();
        // 409 는 키를 남기지 않는다 → 같은 키로 새 가격을 보내면 주문된다
        assertThat(keys.store).isEmpty();
        assertThat(service.placeOrder(command("k1", 1_200L)).created()).isTrue();
    }

    @Test
    void priceDropped_alsoConflicts() {
        catalog.products.put(pid(10), new ProductCatalogPort.CatalogProduct(pid(10), "a", 900L, true));

        assertThatThrownBy(() -> service.placeOrder(command(null, 1_000L)))
                .isInstanceOf(OrderPriceChangedException.class);
    }

    @Test
    void unknownOrStoppedProduct_422() {
        catalog.products.remove(pid(11));
        assertThatThrownBy(() -> service.placeOrder(command(null, 1_000L)))
                .isInstanceOf(ProductNotOrderableException.class).hasMessageContaining(pid(11).toString());

        catalog.products.put(pid(11), new ProductCatalogPort.CatalogProduct(pid(11), "b", 500L, false));
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

        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderCommand(uid(1),
                List.of(new PlaceOrderCommand.Line(pid(10), 0, 1_000L)), 0L, null)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void replay_returnsFirstOrder_evenIfPriceChangedOrCatalogDown() {
        PlaceOrderResult first = service.placeOrder(command("k3", 1_000L));
        catalog.products.put(pid(10), new ProductCatalogPort.CatalogProduct(pid(10), "a", 2_000L, true));
        catalog.down = true;

        PlaceOrderResult again = service.placeOrder(command("k3", 1_000L));

        assertThat(again.created()).isFalse();
        assertThat(again.order().id()).isEqualTo(first.order().id());
        assertThatThrownBy(() -> service.placeOrder(command("k3", 2_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    static final class FakeCatalog implements ProductCatalogPort {

        final Map<UUID, CatalogProduct> products = new HashMap<>();
        boolean down;

        @Override
        public List<CatalogProduct> findByIds(Collection<UUID> productIds) {
            if (down) {
                throw new ProductCatalogUnavailableException(new RuntimeException("down"));
            }
            return productIds.stream().map(products::get).filter(Objects::nonNull).toList();
        }
    }
}
