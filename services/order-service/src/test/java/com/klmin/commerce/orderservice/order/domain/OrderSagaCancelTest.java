package com.klmin.commerce.orderservice.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_APPROVED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_CANCELLED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_REFUNDED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_USED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RELEASED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVATION_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RETURNED;
import static org.assertj.core.api.Assertions.assertThat;

/** 사용자 주문 취소 (ADR 0020): 진행 중이면 다음 정방향 응답에서 보상으로, 완료 후면 결제 취소 → 포인트 환불 → 재고 반환 */
class OrderSagaCancelTest {

    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");
    private static final SagaTimeoutPolicy POLICY = new SagaTimeoutPolicy(Duration.ofSeconds(30), 2, 3);

    /** 합계 10,000 */
    private static OrderSaga start(long pointAmount) {
        Order order = Order.place(UUID.randomUUID(), UserId.of(uid(1)), List.of(OrderLine.of(pid(10), 2, 5_000L)),
                Points.of(pointAmount), T0);
        return OrderSaga.start(order, T0);
    }

    private static SagaAction on(OrderSaga saga, SagaEventType event) {
        return saga.handle(event, event.name().endsWith("FAILED") ? "reason of " + event : null, T0.plusSeconds(1));
    }

    private static OrderSaga completed(long pointAmount) {
        OrderSaga saga = start(pointAmount);
        on(saga, STOCK_RESERVED);
        if (pointAmount > 0) {
            on(saga, POINT_USED);
        }
        if (pointAmount < 10_000L) {
            on(saga, PAYMENT_APPROVED);
        }
        assertThat(saga.state()).isEqualTo(SagaState.COMPLETED);
        return saga;
    }

    @Test
    void whileReservingStock_reservedReplyGoesToRelease() {
        OrderSaga saga = start(3_000L);

        assertThat(saga.requestCancel(T0)).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.state()).isEqualTo(SagaState.RESERVING_STOCK);
        assertThat(saga.cancelRequested()).isTrue();

        assertThat(on(saga, STOCK_RESERVED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel(OrderSaga.USER_CANCEL_REASON, false));
        assertThat(saga.history()).extracting(SagaHistoryEntry::event)
                .containsExactly(OrderSaga.STARTED, OrderSaga.CANCEL_REQUESTED, "STOCK_RESERVED", "STOCK_RELEASED");
    }

    @Test
    void whileReservingStock_failedReplyCancelsWithItsReason() {
        OrderSaga saga = start(0L);
        saga.requestCancel(T0);

        assertThat(on(saga, STOCK_RESERVATION_FAILED))
                .isEqualTo(new SagaAction.Cancel("reason of STOCK_RESERVATION_FAILED", false));
    }

    @Test
    void whileUsingPoint_usedReplyRefundsThenReleases() {
        OrderSaga saga = start(3_000L);
        on(saga, STOCK_RESERVED);
        saga.requestCancel(T0);

        assertThat(on(saga, POINT_USED)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(on(saga, POINT_REFUNDED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel(OrderSaga.USER_CANCEL_REASON, false));
    }

    // 결제는 응답을 기다린다: 승인됐으면 결제 취소(환불)부터 되돌린다 (ADR 0007)
    @Test
    void whilePaying_approvedReplyCancelsPaymentFirst() {
        OrderSaga saga = start(3_000L);
        on(saga, STOCK_RESERVED);
        on(saga, POINT_USED);
        saga.requestCancel(T0);

        assertThat(on(saga, PAYMENT_APPROVED)).isEqualTo(new SagaAction.CancelPayment(Money.of(7_000L)));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_PAYMENT);
        assertThat(on(saga, PAYMENT_CANCELLED)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(on(saga, POINT_REFUNDED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel(OrderSaga.USER_CANCEL_REASON, false));
    }

    @Test
    void whilePaying_failedReplyCompensatesAsUsual() {
        OrderSaga saga = start(0L);
        on(saga, STOCK_RESERVED);
        saga.requestCancel(T0);

        assertThat(on(saga, PAYMENT_FAILED)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel("reason of PAYMENT_FAILED", false));
    }

    @Test
    void whileCompensating_requestIsOnlyRecorded() {
        OrderSaga saga = start(0L);
        on(saga, STOCK_RESERVED);
        on(saga, PAYMENT_FAILED);

        assertThat(saga.requestCancel(T0)).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Cancel("reason of PAYMENT_FAILED", false));
    }

    @Test
    void afterCompletion_refundsPaymentThenPointThenReturnsStock() {
        OrderSaga saga = completed(3_000L);

        assertThat(saga.requestCancel(T0)).isEqualTo(new SagaAction.CancelPayment(Money.of(7_000L)));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_PAYMENT);
        assertThat(saga.afterCompletion()).isTrue();
        assertThat(on(saga, PAYMENT_CANCELLED)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(on(saga, POINT_REFUNDED)).isEqualTo(new SagaAction.ReturnStock());
        assertThat(saga.state()).isEqualTo(SagaState.RETURNING_STOCK);
        // 반환 단계에 해제 응답이 오면 무시한다 (기다리는 응답이 아님)
        assertThat(on(saga, STOCK_RELEASED)).isEqualTo(new SagaAction.Ignore());
        assertThat(on(saga, STOCK_RETURNED)).isEqualTo(new SagaAction.Cancel(OrderSaga.USER_CANCEL_REASON, true));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void afterCompletion_skipsStepsThatDidNotHappen() {
        // 포인트를 쓰지 않음: 결제 취소 → 재고 반환
        OrderSaga paidOnly = completed(0L);
        assertThat(paidOnly.requestCancel(T0)).isEqualTo(new SagaAction.CancelPayment(Money.of(10_000L)));
        assertThat(on(paidOnly, PAYMENT_CANCELLED)).isEqualTo(new SagaAction.ReturnStock());

        // 전액 포인트: 결제 없음 → 포인트 환불 → 재고 반환
        OrderSaga pointsOnly = completed(10_000L);
        assertThat(pointsOnly.requestCancel(T0)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(on(pointsOnly, POINT_REFUNDED)).isEqualTo(new SagaAction.ReturnStock());
    }

    @Test
    void repeatedOrLateRequests_changeNothing() {
        OrderSaga saga = completed(0L);
        saga.requestCancel(T0);
        int historySize = saga.history().size();

        assertThat(saga.requestCancel(T0.plusSeconds(5))).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.history()).hasSize(historySize);

        on(saga, PAYMENT_CANCELLED);
        on(saga, STOCK_RETURNED);
        assertThat(saga.requestCancel(T0.plusSeconds(9))).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    // 완료 후 취소의 보상 단계도 응답이 없으면 포기하지 않고 계속 다시 보낸다
    @Test
    void returningStock_timeoutResendsReturnStock() {
        OrderSaga saga = completed(0L);
        saga.requestCancel(T0);
        on(saga, PAYMENT_CANCELLED);

        Instant later = T0.plusSeconds(1).plus(POLICY.stepTimeout());
        for (int i = 1; i <= 5; i++) {
            assertThat(saga.onTimeout(later, POLICY)).isEqualTo(new SagaAction.ReturnStock());
            later = later.plus(POLICY.stepTimeout());
        }
        assertThat(saga.state()).isEqualTo(SagaState.RETURNING_STOCK);
    }

    @Test
    void restore_keepsCancelFlags() {
        OrderSaga saga = completed(0L);
        saga.requestCancel(T0);

        OrderSaga restored = OrderSaga.restore(saga.orderId(), saga.userId(), saga.pointAmount(), saga.paidAmount(),
                saga.state(), saga.pointUsed(), saga.failureReason(), saga.stepResends(), saga.cancelRequested(),
                saga.afterCompletion(), saga.history(), saga.createdAt(), saga.updatedAt());

        assertThat(on(restored, PAYMENT_CANCELLED)).isEqualTo(new SagaAction.ReturnStock());
    }
}
