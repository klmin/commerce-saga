package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.RequestStockReservationPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final RecordingStockReservation stockReservation = new RecordingStockReservation();
    private final OrderService service =
            new OrderService(orders, orders, stockReservation, Clock.fixed(NOW, ZoneOffset.UTC));

    private Order placeFor(long userId) {
        return service.placeOrder(new PlaceOrderCommand(userId, List.of(
                new PlaceOrderCommand.Line(10L, 2, 1_000L),
                new PlaceOrderCommand.Line(11L, 1, 500L))));
    }

    @Test
    void placeOrder_savesPendingOrderAndRequestsReservation() {
        Order order = placeFor(1L);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.totalAmount()).isEqualTo(2_500L);
        assertThat(orders.store).containsKey(order.id());
        assertThat(stockReservation.requested).containsExactly(order.id());
    }

    @Test
    void getOrder_returnsOwnOrder() {
        Order order = placeFor(1L);

        assertThat(service.getOrder(new GetOrderQuery(order.id(), 1L)).id()).isEqualTo(order.id());
    }

    @Test
    void getOrder_hidesOtherUsersOrder() {
        Order order = placeFor(1L);

        assertThatThrownBy(() -> service.getOrder(new GetOrderQuery(order.id(), 2L)))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.getOrder(new GetOrderQuery(UUID.randomUUID(), 1L)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void stockReserved_confirmsPendingOrder() {
        Order order = placeFor(1L);

        service.stockReserved(order.id());

        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.updates).isEqualTo(1);
    }

    @Test
    void stockReservationFailed_rejectsPendingOrder() {
        Order order = placeFor(1L);

        service.stockReservationFailed(order.id(), "insufficient stock: productId=10");

        Order saved = orders.store.get(order.id());
        assertThat(saved.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(saved.rejectReason()).isEqualTo("insufficient stock: productId=10");
    }

    @Test
    void duplicateAndLateReplies_areIgnored() {
        Order order = placeFor(1L);
        service.stockReserved(order.id());

        service.stockReserved(order.id());
        service.stockReservationFailed(order.id(), "late");

        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.updates).isEqualTo(1);
    }

    @Test
    void replyForUnknownOrder_isIgnored() {
        service.stockReserved(UUID.randomUUID());

        assertThat(orders.updates).isZero();
    }

    /** 저장 시 도메인 객체를 다시 만들어서, 서비스가 저장 후 객체를 몰래 바꿔도 반영되지 않게 한다 */
    static class InMemoryOrders implements LoadOrderPort, SaveOrderPort {

        final Map<UUID, Order> store = new HashMap<>();
        int updates;

        @Override
        public Optional<Order> findById(UUID orderId) {
            return Optional.ofNullable(store.get(orderId)).map(InMemoryOrders::copy);
        }

        @Override
        public void insert(Order order) {
            store.put(order.id(), copy(order));
        }

        @Override
        public void update(Order order) {
            if (!store.containsKey(order.id())) {
                throw new IllegalStateException("not found");
            }
            store.put(order.id(), copy(order));
            updates++;
        }

        private static Order copy(Order o) {
            return Order.restore(o.id(), o.userId(), o.lines(), o.status(), o.rejectReason(),
                    o.createdAt(), o.updatedAt());
        }
    }

    static class RecordingStockReservation implements RequestStockReservationPort {

        final List<UUID> requested = new ArrayList<>();

        @Override
        public void requestReservation(Order order) {
            requested.add(order.id());
        }
    }
}
