package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.SagaNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderSagaPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import com.klmin.commerce.orderservice.order.domain.SagaState;
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

import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_APPROVED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_REFUNDED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_USED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RELEASED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVATION_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final InMemorySagas sagas = new InMemorySagas();
    private final Recording sent = new Recording();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final OrderService service =
            new OrderService(orders, orders, sagas, sent, sent, processed, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 합계 2,500 */
    private Order place(long userId, long pointAmount) {
        return service.placeOrder(new PlaceOrderCommand(userId, List.of(
                new PlaceOrderCommand.Line(10L, 2, 1_000L),
                new PlaceOrderCommand.Line(11L, 1, 500L)), pointAmount));
    }

    private void reply(Order order, SagaEventType type) {
        service.handle(UUID.randomUUID(), new SagaReplyCommand(order.id(), type, type.name().endsWith("FAILED") ? "why" : null));
    }

    @Test
    void placeOrder_savesOrderAndSaga_andRequestsStockReservation() {
        Order order = place(1L, 1_000L);

        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.PENDING);
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.RESERVING_STOCK);
        assertThat(sent.log).containsExactly("reserveStock:" + order.id());
    }

    @Test
    void placeOrder_assignsTimeOrderedUuidV7() {
        Order order = place(1L, 0L);

        assertThat(order.id().version()).isEqualTo(7);
    }

    @Test
    void happyPath_sendsEachCommandOnce_andPublishesOrderCompleted() {
        Order order = place(1L, 1_000L);

        reply(order, STOCK_RESERVED);
        reply(order, POINT_USED);
        reply(order, PAYMENT_APPROVED);

        assertThat(sent.log).containsExactly(
                "reserveStock:" + order.id(),
                "usePoint:" + order.id() + ":1:1000",
                "pay:" + order.id() + ":1:1500",
                "completed:" + order.id());
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void paymentFailed_compensatesInReverseOrder_andPublishesOrderCancelled() {
        Order order = place(1L, 1_000L);
        reply(order, STOCK_RESERVED);
        reply(order, POINT_USED);

        reply(order, PAYMENT_FAILED);
        reply(order, POINT_REFUNDED);
        reply(order, STOCK_RELEASED);

        assertThat(sent.log).endsWith(
                "refundPoint:" + order.id(),
                "releaseStock:" + order.id(),
                "cancelled:" + order.id());
        Order saved = orders.store.get(order.id());
        assertThat(saved.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saved.cancelReason()).isEqualTo("why");
    }

    @Test
    void stockReservationFailed_cancelsImmediately() {
        Order order = place(1L, 0L);

        reply(order, STOCK_RESERVATION_FAILED);

        assertThat(sent.log).containsExactly("reserveStock:" + order.id(), "cancelled:" + order.id());
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void redeliveredReply_isSkippedBeforeTouchingSaga() {
        Order order = place(1L, 0L);
        UUID messageId = UUID.randomUUID();
        SagaReplyCommand reserved = SagaReplyCommand.of(order.id(), STOCK_RESERVED);

        service.handle(messageId, reserved);
        int before = sent.log.size();
        service.handle(messageId, reserved);

        assertThat(sent.log).hasSize(before);
        assertThat(sagas.updates).isEqualTo(1);
        assertThat(processed.ids).containsExactly(messageId);
    }

    @Test
    void duplicateAndLateReplies_doNotSendAnything() {
        Order order = place(1L, 0L);
        reply(order, STOCK_RESERVED);
        int before = sent.log.size();

        reply(order, STOCK_RESERVED);
        reply(order, POINT_REFUNDED);
        reply(order, STOCK_RELEASED);

        assertThat(sent.log).hasSize(before);
        assertThat(sagas.updates).isEqualTo(1);
    }

    @Test
    void replyForUnknownOrder_isIgnored() {
        service.handle(UUID.randomUUID(), SagaReplyCommand.of(UUID.randomUUID(), STOCK_RESERVED));

        assertThat(sent.log).isEmpty();
    }

    @Test
    void getSaga_onlyOwner() {
        Order order = place(1L, 0L);

        assertThat(service.getSaga(new GetOrderQuery(order.id(), 1L)).state()).isEqualTo(SagaState.RESERVING_STOCK);
        assertThatThrownBy(() -> service.getSaga(new GetOrderQuery(order.id(), 2L)))
                .isInstanceOf(SagaNotFoundException.class);
    }

    @Test
    void getOrder_hidesOtherUsersOrder() {
        Order order = place(1L, 0L);

        assertThat(service.getOrder(new GetOrderQuery(order.id(), 1L)).id()).isEqualTo(order.id());
        assertThatThrownBy(() -> service.getOrder(new GetOrderQuery(order.id(), 2L)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    static class InMemoryOrders implements LoadOrderPort, SaveOrderPort {

        final Map<UUID, Order> store = new HashMap<>();

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
            store.put(order.id(), copy(order));
        }

        private static Order copy(Order o) {
            return Order.restore(o.id(), o.userId(), o.lines(), o.pointAmount(), o.status(), o.cancelReason(),
                    o.createdAt(), o.updatedAt());
        }
    }

    static class InMemorySagas implements OrderSagaPort {

        final Map<UUID, OrderSaga> store = new HashMap<>();
        int updates;

        @Override
        public Optional<OrderSaga> findByOrderId(UUID orderId) {
            return Optional.ofNullable(store.get(orderId)).map(InMemorySagas::copy);
        }

        @Override
        public Optional<OrderSaga> findByOrderIdForUpdate(UUID orderId) {
            return findByOrderId(orderId);
        }

        @Override
        public List<UUID> findStuckOrderIds(Instant updatedBefore, int limit) {
            return store.values().stream()
                    .filter(s -> !s.state().isFinished() && !s.updatedAt().isAfter(updatedBefore))
                    .sorted(java.util.Comparator.comparing(OrderSaga::updatedAt))
                    .limit(limit)
                    .map(OrderSaga::orderId)
                    .toList();
        }

        @Override
        public void insert(OrderSaga saga) {
            store.put(saga.orderId(), copy(saga));
        }

        @Override
        public void update(OrderSaga saga) {
            store.put(saga.orderId(), copy(saga));
            updates++;
        }

        private static OrderSaga copy(OrderSaga s) {
            return OrderSaga.restore(s.orderId(), s.userId(), s.pointAmount(), s.paidAmount(), s.state(),
                    s.pointUsed(), s.failureReason(), s.stepResends(), s.history(), s.createdAt(), s.updatedAt());
        }
    }

    static class Recording implements SagaCommandPort, OrderEventPort {

        final List<String> log = new ArrayList<>();

        @Override
        public void reserveStock(Order order) {
            log.add("reserveStock:" + order.id());
        }

        @Override
        public void releaseStock(UUID orderId) {
            log.add("releaseStock:" + orderId);
        }

        @Override
        public void usePoint(UUID orderId, long userId, long amount) {
            log.add("usePoint:" + orderId + ":" + userId + ":" + amount);
        }

        @Override
        public void refundPoint(UUID orderId) {
            log.add("refundPoint:" + orderId);
        }

        @Override
        public void pay(UUID orderId, long userId, long amount) {
            log.add("pay:" + orderId + ":" + userId + ":" + amount);
        }

        @Override
        public void orderCompleted(Order order) {
            log.add("completed:" + order.id());
        }

        @Override
        public void orderCancelled(Order order) {
            log.add("cancelled:" + order.id());
        }
    }
}
