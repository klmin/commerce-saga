package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.application.service.OrderServiceTest.InMemoryOrders;
import com.klmin.commerce.orderservice.order.application.service.OrderServiceTest.InMemorySagas;
import com.klmin.commerce.orderservice.order.application.service.OrderServiceTest.Recording;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderStatus;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SagaTimeoutServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-23T00:00:00Z");
    private static final Duration STEP = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryOrders orders = new InMemoryOrders();
    private final InMemorySagas sagas = new InMemorySagas();
    private final Recording sent = new Recording();
    private final OrderService orderService =
            new OrderService(orders, orders, sagas, sent, sent, new InMemoryProcessedMessages(), clock);
    private final SagaTimeoutService timeouts = new SagaTimeoutService(orders, orders, sagas, sent, sent,
            new SagaTimeoutProperties(STEP, 1, 2, 100), clock);

    private Order place(long pointAmount) {
        return orderService.placeOrder(new PlaceOrderCommand(1L, List.of(
                new PlaceOrderCommand.Line(10L, 2, 1_000L)), pointAmount));
    }

    private void reply(Order order, SagaEventType type) {
        orderService.handle(UUID.randomUUID(), SagaReplyCommand.of(order.id(), type));
    }

    /** 스케줄러 한 번 분량 */
    private void scan() {
        timeouts.findStuckSagas().forEach(timeouts::handleTimeout);
    }

    @Test
    void stuckSagaIsFound_onlyAfterStepTimeout() {
        Order order = place(0L);

        clock.advance(STEP.minusSeconds(1));
        assertThat(timeouts.findStuckSagas()).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        assertThat(timeouts.findStuckSagas()).containsExactly(order.id());
    }

    @Test
    void resendsCommand_thenCompensates_andCancelsOrder() {
        Order order = place(0L);

        clock.advance(STEP);
        scan();
        assertThat(sent.log).containsExactly("reserveStock:" + order.id(), "reserveStock:" + order.id());

        clock.advance(STEP);
        scan();
        assertThat(sent.log).last().isEqualTo("releaseStock:" + order.id());
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.RELEASING_STOCK);
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.PENDING);

        reply(order, SagaEventType.STOCK_RELEASED);
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.store.get(order.id()).cancelReason())
                .isEqualTo("timeout: no reply in RESERVING_STOCK after 1 resends");
        assertThat(sent.log).last().isEqualTo("cancelled:" + order.id());
    }

    @Test
    void replyBetweenScanAndLock_winsOverTimeout() {
        Order order = place(0L);
        clock.advance(STEP);
        List<UUID> stuck = timeouts.findStuckSagas();

        // 스캔과 잠금 사이에 응답이 와서 다음 단계로 갔다
        reply(order, SagaEventType.STOCK_RESERVED);
        int before = sent.log.size();

        assertThat(timeouts.handleTimeout(stuck.getFirst())).isFalse();
        assertThat(sent.log).hasSize(before);
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.PAYING);
    }

    @Test
    void secondHandlerForSameSaga_doesNothing() {
        Order order = place(0L);
        clock.advance(STEP);

        assertThat(timeouts.handleTimeout(order.id())).isTrue();
        // 다른 인스턴스가 같은 스캔 결과로 다시 처리하려 해도, 방금 재전송해 updatedAt 이 바뀌었으므로 건너뛴다
        assertThat(timeouts.handleTimeout(order.id())).isFalse();
        assertThat(sent.log).containsExactly("reserveStock:" + order.id(), "reserveStock:" + order.id());
    }

    @Test
    void paying_asksAgain_thenCancelsPayment() {
        Order order = place(0L);
        reply(order, SagaEventType.STOCK_RESERVED);

        for (int i = 0; i < 3; i++) {
            clock.advance(STEP);
            scan();
        }

        assertThat(sent.log).endsWith("pay:" + order.id() + ":1:2000", "pay:" + order.id() + ":1:2000",
                "cancelPayment:" + order.id() + ":1:2000");
        assertThat(sagas.store.get(order.id()).state()).isEqualTo(SagaState.CANCELLING_PAYMENT);

        reply(order, SagaEventType.PAYMENT_CANCELLED);
        reply(order, SagaEventType.STOCK_RELEASED);
        assertThat(orders.store.get(order.id()).status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.store.get(order.id()).cancelReason()).isEqualTo("timeout: no reply in PAYING after 2 resends");
    }

    @Test
    void finishedSagas_areNotScanned() {
        Order order = place(0L);
        reply(order, SagaEventType.STOCK_RESERVATION_FAILED);

        clock.advance(Duration.ofDays(1));

        assertThat(timeouts.findStuckSagas()).isEmpty();
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
