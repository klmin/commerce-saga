package com.klmin.commerce.orderservice.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_APPROVED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_CANCELLED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.PAYMENT_FAILED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_REFUNDED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.POINT_USED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RELEASED;
import static com.klmin.commerce.orderservice.order.domain.SagaEventType.STOCK_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;

class OrderSagaTimeoutTest {

    private static final Instant T0 = Instant.parse("2026-11-23T00:00:00Z");
    private static final Duration STEP = Duration.ofSeconds(30);
    private static final int MAX_RESENDS = 2;
    private static final int MAX_PAYMENT_RESENDS = 4;
    private static final SagaTimeoutPolicy POLICY = new SagaTimeoutPolicy(STEP, MAX_RESENDS, MAX_PAYMENT_RESENDS);

    /** 합계 10,000 */
    private static OrderSaga start(long pointAmount) {
        Order order = Order.place(UUID.randomUUID(), 1L, List.of(new OrderLine(10L, 2, 5_000L)), pointAmount, T0);
        return OrderSaga.start(order, T0);
    }

    /** 마지막 변경에서 STEP 이 지난 시각에 타임아웃 */
    private static SagaAction timeout(OrderSaga saga) {
        return saga.onTimeout(saga.updatedAt().plus(STEP), POLICY);
    }

    private static void reply(OrderSaga saga, SagaEventType event) {
        saga.handle(event, event.name().endsWith("FAILED") ? "why" : null, saga.updatedAt().plusSeconds(1));
    }

    @Test
    void beforeStepTimeout_doesNothing() {
        OrderSaga saga = start(0L);

        SagaAction action = saga.onTimeout(T0.plus(STEP).minusMillis(1), POLICY);

        assertThat(action).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.history()).hasSize(1);
        assertThat(saga.updatedAt()).isEqualTo(T0);
    }

    @Test
    void reservingStock_resendsSameCommand_thenGivesUpWithRelease() {
        OrderSaga saga = start(0L);

        assertThat(timeout(saga)).isEqualTo(new SagaAction.ReserveStock());
        assertThat(timeout(saga)).isEqualTo(new SagaAction.ReserveStock());
        assertThat(saga.stepResends()).isEqualTo(2);
        assertThat(saga.state()).isEqualTo(SagaState.RESERVING_STOCK);

        // 예약됐는지 모르므로 해제부터 한다 (해제는 예약이 없었어도 안전)
        assertThat(timeout(saga)).isEqualTo(new SagaAction.ReleaseStock());
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
        assertThat(saga.stepResends()).isZero();
        assertThat(saga.failureReason()).isEqualTo("timeout: no reply in RESERVING_STOCK after 2 resends");
        assertThat(saga.history()).extracting(SagaHistoryEntry::event).containsExactly(
                OrderSaga.STARTED, OrderSaga.TIMEOUT_RESEND, OrderSaga.TIMEOUT_RESEND, OrderSaga.TIMEOUT_GIVE_UP);

        reply(saga, STOCK_RELEASED);
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
    }

    @Test
    void usingPoint_givesUpWithRefundEvenIfPointUseUnknown() {
        OrderSaga saga = start(3_000L);
        reply(saga, STOCK_RESERVED);

        assertThat(timeout(saga)).isEqualTo(new SagaAction.UsePoint(3_000L));
        assertThat(timeout(saga)).isEqualTo(new SagaAction.UsePoint(3_000L));
        // 차감됐는지 모른다. 환불은 차감 기록이 없어도 안전하므로 환불 → 재고 해제 순으로 되돌린다
        assertThat(timeout(saga)).isEqualTo(new SagaAction.RefundPoint());
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);

        reply(saga, POINT_REFUNDED);
        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
    }

    @Test
    void lateReplyAfterGiveUp_isIgnored() {
        OrderSaga saga = start(3_000L);
        reply(saga, STOCK_RESERVED);
        timeout(saga);
        timeout(saga);
        timeout(saga);
        int historySize = saga.history().size();

        // 포기한 뒤 늦게 온 차감 성공 응답은 단계를 되돌리지 않는다 (환불이 이미 나갔다)
        SagaAction action = saga.handle(POINT_USED, null, saga.updatedAt().plusSeconds(1));

        assertThat(action).isEqualTo(new SagaAction.Ignore());
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);
        assertThat(saga.history()).hasSize(historySize);
    }

    @Test
    void paying_asksLonger_thenCancelsPaymentFirst() {
        OrderSaga saga = start(3_000L);
        reply(saga, STOCK_RESERVED);
        reply(saga, POINT_USED);

        // 결과가 애매하므로 재고·포인트보다 오래 다시 묻는다
        for (int i = 0; i < MAX_PAYMENT_RESENDS; i++) {
            assertThat(timeout(saga)).isEqualTo(new SagaAction.Pay(7_000L));
        }
        assertThat(saga.state()).isEqualTo(SagaState.PAYING);

        // 그래도 응답이 없으면 결제 취소부터. 승인됐다면 결제 서비스가 환불한다
        assertThat(timeout(saga)).isEqualTo(new SagaAction.CancelPayment(7_000L));
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_PAYMENT);
        assertThat(saga.state().isCompensating()).isTrue();
        assertThat(saga.failureReason()).isEqualTo("timeout: no reply in PAYING after 4 resends");

        reply(saga, PAYMENT_CANCELLED);
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);
        reply(saga, POINT_REFUNDED);
        reply(saga, STOCK_RELEASED);
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);
        // 처음 실패 사유(결제 타임아웃)가 남는다
        assertThat(saga.failureReason()).isEqualTo("timeout: no reply in PAYING after 4 resends");
    }

    @Test
    void cancellingPayment_withoutPointUse_goesToReleaseStock() {
        OrderSaga saga = start(0L);
        reply(saga, STOCK_RESERVED);
        for (int i = 0; i <= MAX_PAYMENT_RESENDS; i++) {
            timeout(saga);
        }

        reply(saga, PAYMENT_CANCELLED);

        assertThat(saga.state()).isEqualTo(SagaState.RELEASING_STOCK);
    }

    @Test
    void cancellingPayment_ignoresLateApprovalOrFailure_andKeepsResendingCancel() {
        OrderSaga saga = start(0L);
        reply(saga, STOCK_RESERVED);
        for (int i = 0; i <= MAX_PAYMENT_RESENDS; i++) {
            timeout(saga);
        }
        int historySize = saga.history().size();

        // 취소를 보낸 뒤 늦게 온 승인·거절은 단계를 되돌리지 않는다 (승인분은 결제 서비스가 환불)
        assertThat(saga.handle(PAYMENT_APPROVED, null, saga.updatedAt().plusSeconds(1)))
                .isEqualTo(new SagaAction.Ignore());
        assertThat(saga.handle(PAYMENT_FAILED, "late", saga.updatedAt().plusSeconds(1)))
                .isEqualTo(new SagaAction.Ignore());
        assertThat(saga.history()).hasSize(historySize);

        // 결제 취소도 보상이라 포기하지 않는다
        for (int i = 0; i < MAX_PAYMENT_RESENDS + 3; i++) {
            assertThat(timeout(saga)).isEqualTo(new SagaAction.CancelPayment(10_000L));
        }
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLING_PAYMENT);
    }
    @Test
    void compensation_keepsResendingForever() {
        OrderSaga saga = start(3_000L);
        reply(saga, STOCK_RESERVED);
        reply(saga, POINT_USED);
        reply(saga, PAYMENT_FAILED);
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);

        for (int i = 0; i < MAX_RESENDS + 3; i++) {
            assertThat(timeout(saga)).isEqualTo(new SagaAction.RefundPoint());
        }
        assertThat(saga.state()).isEqualTo(SagaState.REFUNDING_POINT);
        // 처음 실패 사유가 유지된다
        assertThat(saga.failureReason()).isEqualTo("why");

        reply(saga, POINT_REFUNDED);
        assertThat(timeout(saga)).isEqualTo(new SagaAction.ReleaseStock());
    }

    @Test
    void replyResetsResendCount_forNextStep() {
        OrderSaga saga = start(3_000L);
        timeout(saga);
        timeout(saga);

        reply(saga, STOCK_RESERVED);

        assertThat(saga.stepResends()).isZero();
        assertThat(timeout(saga)).isEqualTo(new SagaAction.UsePoint(3_000L));
    }

    @Test
    void finishedSaga_isNeverTimedOut() {
        OrderSaga saga = start(0L);
        reply(saga, SagaEventType.STOCK_RESERVATION_FAILED);
        assertThat(saga.state()).isEqualTo(SagaState.CANCELLED);

        assertThat(saga.onTimeout(saga.updatedAt().plus(Duration.ofDays(1)), POLICY))
                .isEqualTo(new SagaAction.Ignore());
    }

    @Test
    void zeroMaxResends_givesUpOnFirstTimeout() {
        OrderSaga saga = start(0L);

        assertThat(saga.onTimeout(T0.plus(STEP), new SagaTimeoutPolicy(STEP, 0, 0))).isEqualTo(new SagaAction.ReleaseStock());
    }
}
