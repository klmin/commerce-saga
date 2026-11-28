package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.CancelOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.CancelOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 주문 취소 유스케이스 (ADR 0020): 소유권, 진행 중·완료 후 흐름, 멱등, 주문 상태·이벤트 */
class CancelOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-28T00:00:00Z");

    private final OrderServiceTest.InMemoryOrders orders = new OrderServiceTest.InMemoryOrders();
    private final OrderServiceTest.InMemorySagas sagas = new OrderServiceTest.InMemorySagas();
    private final OrderServiceTest.Recording sent = new OrderServiceTest.Recording();
    private final InMemorySagaOutcomes outcomes = new InMemorySagaOutcomes();
    private final OrderService service = new OrderService(orders, orders, sagas, sent, sent,
            new InMemoryProcessedMessages(), new InMemoryIdempotencyKeys(), new InMemoryCart(),
            outcomes, Clock.fixed(NOW, ZoneOffset.UTC));

    /** 합계 2,500 */
    private Order place(long pointAmount) {
        return service.placeOrder(new PlaceOrderCommand(uid(1), List.of(
                new PlaceOrderCommand.Line(pid(10), 2, 1_000L),
                new PlaceOrderCommand.Line(pid(11), 1, 500L)), pointAmount)).order();
    }

    private void reply(Order order, SagaEventType type) {
        service.handle(UUID.randomUUID(), new SagaReplyCommand(order.id(), type, null));
    }

    private CancelOrderResult cancel(Order order) {
        return service.cancelOrder(new CancelOrderCommand(order.id(), uid(1)));
    }

    @Test
    void othersOrder_isNotFound_andNothingChanges() {
        Order order = place(0L);

        assertThatThrownBy(() -> service.cancelOrder(new CancelOrderCommand(order.id(), uid(2))))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service.cancelOrder(new CancelOrderCommand(UUID.randomUUID(), uid(1))))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(sagas.store.get(order.id()).cancelRequested()).isFalse();
        assertThat(orders.store.get(order.id()).cancelRequestedAt()).isNull();
    }

    @Test
    void inProgress_recordsRequest_thenNextReplyCompensates() {
        Order order = place(0L);
        sent.log.clear();

        CancelOrderResult result = cancel(order);

        assertThat(result.inProgress()).isTrue();
        assertThat(sent.log).isEmpty();
        assertThat(orders.store.get(order.id()).cancelRequestedAt()).isEqualTo(NOW);
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.PENDING);

        reply(order, SagaEventType.STOCK_RESERVED);
        reply(order, SagaEventType.STOCK_RELEASED);

        assertThat(sent.log).containsExactly("releaseStock:" + order.id(), "cancelled:" + order.id());
        Order cancelled = orders.store.get(order.id());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(OrderSaga.USER_CANCEL_REASON);
        assertThat(outcomes.recorded).extracting(InMemorySagaOutcomes.Outcome::result)
                .containsExactly(SagaState.CANCELLED);
    }

    @Test
    void afterCompletion_refundsAndReturnsStock_andPublishesAfterCompletion() {
        Order order = place(1_000L);
        reply(order, SagaEventType.STOCK_RESERVED);
        reply(order, SagaEventType.POINT_USED);
        reply(order, SagaEventType.PAYMENT_APPROVED);
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.COMPLETED);
        sent.log.clear();

        assertThat(cancel(order).inProgress()).isTrue();
        assertThat(sent.log).containsExactly("cancelPayment:" + order.id() + ":" + uid(1) + ":1500");
        // 취소가 끝나기 전에는 완료 상태 그대로 (취소 요청됨만 표시)
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.COMPLETED);

        reply(order, SagaEventType.PAYMENT_CANCELLED);
        reply(order, SagaEventType.POINT_REFUNDED);
        reply(order, SagaEventType.STOCK_RETURNED);

        assertThat(sent.log).containsExactly(
                "cancelPayment:" + order.id() + ":" + uid(1) + ":1500",
                "refundPoint:" + order.id(),
                "returnStock:" + order.id(),
                "cancelled:" + order.id() + ":afterCompletion");
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.CANCELLED);
        // Saga 처리 시간 지표는 완료 때 한 번만 (완료 후 취소는 다시 세지 않는다)
        assertThat(outcomes.recorded).extracting(InMemorySagaOutcomes.Outcome::result)
                .containsExactly(SagaState.COMPLETED);
    }

    @Test
    void repeatedRequest_sendsCommandsOnce_andCancelledOrderReturnsNotInProgress() {
        Order order = place(0L);
        reply(order, SagaEventType.STOCK_RESERVED);
        reply(order, SagaEventType.PAYMENT_APPROVED);
        sent.log.clear();

        cancel(order);
        cancel(order);
        assertThat(sent.log).containsExactly("cancelPayment:" + order.id() + ":" + uid(1) + ":2500");

        reply(order, SagaEventType.PAYMENT_CANCELLED);
        reply(order, SagaEventType.STOCK_RETURNED);
        CancelOrderResult again = cancel(order);

        assertThat(again.inProgress()).isFalse();
        assertThat(again.order().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(sent.log).filteredOn(s -> s.startsWith("cancelled:")).hasSize(1);
    }

    // 실패로 이미 취소된 주문: 아무것도 보내지 않고 취소됨으로 응답
    @Test
    void alreadyFailedOrder_isNotInProgress() {
        Order order = place(0L);
        reply(order, SagaEventType.STOCK_RESERVATION_FAILED);
        sent.log.clear();

        CancelOrderResult result = cancel(order);

        assertThat(result.inProgress()).isFalse();
        assertThat(sent.log).isEmpty();
        assertThat(orders.store.get(order.id()).cancelRequestedAt()).isNull();
    }
}
