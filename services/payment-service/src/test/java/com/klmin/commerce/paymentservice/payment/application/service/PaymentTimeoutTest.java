package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.service.PaymentServiceTest.InMemoryPayments;
import com.klmin.commerce.paymentservice.payment.application.service.PaymentServiceTest.RecordingReplies;
import com.klmin.commerce.paymentservice.payment.application.service.PaymentServiceTest.StubGateway;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import com.klmin.commerce.paymentservice.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PG 타임아웃 → UNKNOWN → PG 조회로 확정, 결제 취소, 늦은 승인 자동 환불 (ADR 0007) */
class PaymentTimeoutTest {

    private static final Instant T0 = Instant.parse("2026-11-23T00:00:00Z");
    private static final Duration NOT_FOUND_AFTER = Duration.ofMinutes(2);

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryPayments payments = new InMemoryPayments();
    private final RecordingReplies replies = new RecordingReplies();
    private final StubGateway gateway = new StubGateway();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final PaymentRecorder recorder = new PaymentRecorder(payments, replies, processed, clock);
    private final PaymentService service = new PaymentService(recorder, gateway, gateway, payments, processed);
    private final PaymentReconcileService reconciler = new PaymentReconcileService(payments, gateway, recorder,
            new PaymentReconcileProperties(NOT_FOUND_AFTER, 100), clock);

    private final UUID orderId = UUID.randomUUID();

    private void pay() {
        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));
    }

    private void cancel() {
        service.cancel(UUID.randomUUID(), new CancelPaymentCommand(orderId, 1L, 5_000L));
    }

    /** 스케줄러 한 번 분량 */
    private void reconcileAll() {
        reconciler.findPending().forEach(reconciler::reconcile);
    }

    private Payment payment() {
        return payments.findByOrderId(orderId).orElseThrow();
    }

    /** PG 가 타임아웃 뒤에도 계속 처리해 나중에 결과를 남긴 상황 */
    private void pgFinishesLater(GatewayResult result) {
        gateway.pgLedger.put(orderId, result);
    }

    @Test
    void pgTimeout_recordsUnknown_withoutReplying() {
        gateway.timeout = true;

        pay();

        assertThat(payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(replies.log).isEmpty();
        assertThat(reconciler.findPending()).containsExactly(orderId);
    }

    @Test
    void resentPay_whileUnknown_doesNotCallPgAgainNorReply() {
        gateway.timeout = true;
        pay();

        pay();

        assertThat(gateway.calls).isEqualTo(1);
        assertThat(replies.log).isEmpty();
    }

    @Test
    void reconcile_pgApprovedLater_confirmsAndRepliesApproved() {
        gateway.timeout = true;
        pay();
        reconcileAll();
        assertThat(payment().status()).isEqualTo(PaymentStatus.UNKNOWN);

        pgFinishesLater(GatewayResult.approved("tx-late"));
        reconcileAll();

        assertThat(payment().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment().transactionId()).isEqualTo("tx-late");
        assertThat(replies.log).containsExactly("approved:" + orderId + ":" + payment().id());
        assertThat(reconciler.findPending()).isEmpty();
        // 다시 돌려도 응답이 두 번 나가지 않는다
        reconcileAll();
        assertThat(replies.log).hasSize(1);
    }

    @Test
    void reconcile_pgDeclinedLater_repliesFailed() {
        gateway.timeout = true;
        pay();

        pgFinishesLater(GatewayResult.declined("card declined"));
        reconcileAll();

        assertThat(payment().status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":card declined");
    }

    @Test
    void reconcile_noPgTransaction_waitsThenDeclines() {
        gateway.timeout = true;
        pay();

        clock.advance(NOT_FOUND_AFTER);
        reconcileAll();
        assertThat(payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(replies.log).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        reconcileAll();
        assertThat(payment().status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":no PG transaction after 120s");
    }

    @Test
    void cancelWhileUnknown_thenLateApproval_refundsWithoutReplyingApproved() {
        gateway.timeout = true;
        pay();

        cancel();
        assertThat(replies.log).containsExactly("cancelled:" + orderId);
        assertThat(payment().cancelRequested()).isTrue();

        // 취소 후 PG 가 늦게 승인했다 → 승인 응답 없이 환불
        pgFinishesLater(GatewayResult.approved("tx-late"));
        reconcileAll();

        assertThat(payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gateway.refunds).containsExactly(orderId);
        assertThat(replies.log).containsExactly("cancelled:" + orderId);
        assertThat(reconciler.findPending()).isEmpty();
    }

    @Test
    void cancelWhileUnknown_thenLateDecline_needsNoRefund() {
        gateway.timeout = true;
        pay();
        cancel();

        pgFinishesLater(GatewayResult.declined("declined"));
        reconcileAll();

        assertThat(payment().status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(gateway.refunds).isEmpty();
        assertThat(replies.log).containsExactly("cancelled:" + orderId);
    }

    @Test
    void cancelApprovedPayment_refundsOnReconcile() {
        pay();
        assertThat(payment().status()).isEqualTo(PaymentStatus.APPROVED);

        cancel();
        assertThat(payment().needsRefund()).isTrue();
        reconcileAll();

        assertThat(payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gateway.refunds).containsExactly(orderId);
        assertThat(replies.log).endsWith("cancelled:" + orderId);
    }

    @Test
    void cancelWithoutPayment_blocksLatePay() {
        cancel();
        assertThat(payment().status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(replies.log).containsExactly("cancelled:" + orderId);

        // 취소 뒤에 늦게 온 Pay: PG 를 부르지 않고 실패로 응답한다
        pay();

        assertThat(gateway.calls).isZero();
        assertThat(payment().status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(replies.log).endsWith("failed:" + orderId + ":" + PaymentRecorder.CANCELLED_REASON);
    }

    @Test
    void repeatedCancel_repliesAgain_withoutChangingState() {
        pay();
        cancel();
        reconcileAll();

        cancel();
        reconcileAll();

        assertThat(gateway.refunds).hasSize(1);
        assertThat(payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(replies.log).filteredOn(s -> s.startsWith("cancelled:")).hasSize(2);
    }

    @Test
    void redeliveredCancel_isSkipped() {
        UUID messageId = UUID.randomUUID();
        CancelPaymentCommand command = new CancelPaymentCommand(orderId, 1L, 5_000L);

        service.cancel(messageId, command);
        service.cancel(messageId, command);

        assertThat(replies.log).containsExactly("cancelled:" + orderId);
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
