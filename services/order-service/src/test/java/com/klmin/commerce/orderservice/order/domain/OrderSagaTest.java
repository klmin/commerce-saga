package com.klmin.commerce.orderservice.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_APPROVED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_REFUNDED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_USED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_USE_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RELEASED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVATION_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;

class OrderSagaTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    /** 합계 10,000 */
    private static OrderSaga start(long pointAmount) {
        Order order = Order.place(UUID.randomUUID(), UserId.of(1L), List.of(OrderLine.of(10L, 2, 5_000L)),
                Points.of(pointAmount), T0);
        return OrderSaga.start(order, T0);
    }

    private static SagaAction on(OrderSaga saga, SagaEventType event) {
        return saga.handle(event, event.name().endsWith("FAILED") ? "reason of " + event : null, T0.plusSeconds(1));
    }

    @Test
    void happyPath_withPointsAndPayment() {
        OrderSaga saga = start(3_000L);
        assertThat(saga.state()).isEqualTo(SagaState.RESERVING_STOCK);

        assertThat(on(saga, STOCK_RESERVED)).isEqualTo(new SagaAction.UsePoint(Points.of(3_000L)));
        assertThat(on(saga, POINT_USED)).isEqualTo(new SagaAction.Pay(Money.of(7_000L)));
        assertThat(on(saga, PAYMENT_APPROVED)).isEqualTo(new SagaAction.Complete());

        assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.history()).extracting(SagaHistoryEntry::state).containsExactly(
                SagaState.RESERVING_STOCK, SagaState.USING_POINT, SagaState.PAYING, SagaState.COMPLETED);
    }

    @Test
    void noPoints_skipsPointStep() {
        OrderSaga saga = start(0L);

        assertThat(on(saga, STOCK_RESERVED)).isEqualTo(new SagaAction.Pay(Money.of(10_000L)));
    }

    @Test
    void fullyPaidByPoints_skipsPayment() {
        OrderSaga saga = start(10_000L);

        on(saga, STOCK_RESERVED);
        assertThat(on(saga, POINT_USED)).isEqualTo(new SagaAction.Complete());
        assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void stockReservationFailed_cancelsWithoutCompensation() {
        OrderSaga saga = start(3_000L);

        SagaAction action = on(saga, STOCK_RESERVATION_FAILED);

        assertThat(action).isEqualTo(new SagaAction.Cancel("reason of STOCK_RESERVATION_FAILED"));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void pointUseFailed_releasesStockOnly() {
        OrderSaga saga = start(3_000L);
        on(saga, STOCK_RESERVED);

        assertThat(on(saga, POINT_USE_FAILED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel("reason of POINT_USE_FAILED"));
    }

    @Test
    void paymentFailed_afterPoints_refundsThenReleasesInReverseOrder() {
        OrderSaga saga = start(3_000L);
        on(saga, STOCK_RESERVED);
        on(saga, POINT_USED);

        assertThat(on(saga, PAYMENT_FAILED)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);
        assertThat(on(saga, POINT_REFUNDED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel("reason of PAYMENT_FAILED"));

        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
        assertThat(saga.failureReason()).isEqualTo("reason of PAYMENT_FAILED");
        assertThat(saga.history()).extracting(SagaHistoryEntry::state).containsExactly(
                SagaState.RESERVING_STOCK, SagaState.USING_POINT, SagaState.PAYING, SagaState.REFUNDING_POINT,
                SagaState.RELEASING_STOCK, SagaState.CANCELLED);
    }

    @Test
    void paymentFailed_withoutPoints_skipsRefund() {
        OrderSaga saga = start(0L);
        on(saga, STOCK_RESERVED);

        assertThat(on(saga, PAYMENT_FAILED)).isEqualTo(new SagaAction.ReleaseStock());
    }

    @Test
    void duplicateReply_isIgnored_andDoesNotSendCommandTwice() {
        OrderSaga saga = start(3_000L);
        on(saga, STOCK_RESERVED);

        assertThat(on(saga, STOCK_RESERVED)).isInstanceOf(SagaAction.Ignore.class);
        assertThat(saga.state()).isEqualTo(SagaState.USING_POINT);
        assertThat(saga.history()).hasSize(2);
    }

    @Test
    void lateApprovalDuringCompensation_isIgnored() {
        OrderSaga saga = start(0L);
        on(saga, STOCK_RESERVED);
        on(saga, PAYMENT_FAILED);

        assertThat(on(saga, PAYMENT_APPROVED)).isInstanceOf(SagaAction.Ignore.class);
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
    }

    @ParameterizedTest
    @EnumSource(SagaEventType.class)
    void finishedSaga_ignoresEverything(SagaEventType event) {
        OrderSaga saga = start(0L);
        on(saga, STOCK_RESERVATION_FAILED);

        assertThat(on(saga, event)).isInstanceOf(SagaAction.Ignore.class);
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }
}
