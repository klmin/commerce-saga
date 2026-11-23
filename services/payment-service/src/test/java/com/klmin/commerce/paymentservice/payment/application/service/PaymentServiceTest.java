package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException;
import com.klmin.commerce.paymentservice.payment.application.PaymentNotFoundException;
import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentQuery;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayFaultPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.SendPaymentReplyPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import com.klmin.commerce.paymentservice.payment.domain.PaymentStatus;
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

class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final RecordingReplies replies = new RecordingReplies();
    private final StubGateway gateway = new StubGateway();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final PaymentService service = new PaymentService(
            new PaymentRecorder(payments, replies, processed, Clock.fixed(NOW, ZoneOffset.UTC)), gateway, gateway, payments,
            processed);

    @Test
    void pay_approved_recordsAndRepliesApproved() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));

        Payment payment = payments.store.get(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.id().version()).isEqualTo(7);
        assertThat(replies.log).containsExactly("approved:" + orderId + ":" + payment.id());
    }

    @Test
    void pay_declined_recordsAndRepliesFailed() {
        gateway.change(new Settings(Mode.DECLINE, 0));
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));

        assertThat(payments.store.get(orderId).status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":declined");
    }

    @Test
    void duplicatePay_doesNotCallGatewayAgain_andRepliesSameResult() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));
        gateway.change(new Settings(Mode.DECLINE, 0));
        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));

        assertThat(gateway.calls).isEqualTo(1);
        assertThat(replies.log).hasSize(2).allMatch(s -> s.startsWith("approved:"));
    }

    @Test
    void redeliveredMessage_isSkipped_withoutCallingGatewayOrReplying() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.pay(messageId, new PayCommand(orderId, 1L, 5_000L));
        service.pay(messageId, new PayCommand(orderId, 1L, 5_000L));

        assertThat(gateway.calls).isEqualTo(1);
        assertThat(replies.log).hasSize(1);
        assertThat(processed.ids).containsExactly(messageId);
    }

    @Test
    void redeliveredInvalidCommand_repliesOnce() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.pay(messageId, new PayCommand(orderId, 1L, 0L));
        service.pay(messageId, new PayCommand(orderId, 1L, 0L));

        assertThat(replies.log).hasSize(1);
    }

    @Test
    void invalidAmount_repliesFailedWithoutCallingGateway() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 0L));

        assertThat(gateway.calls).isZero();
        assertThat(payments.store).isEmpty();
        assertThat(replies.log).containsExactly("failed:" + orderId + ":invalid command: amount must be positive");
    }

    @Test
    void getPayment_onlyOwner() {
        UUID orderId = UUID.randomUUID();
        service.pay(UUID.randomUUID(), new PayCommand(orderId, 1L, 5_000L));

        assertThat(service.getPayment(new GetPaymentQuery(orderId, 1L)).amount()).isEqualTo(5_000L);
        assertThatThrownBy(() -> service.getPayment(new GetPaymentQuery(orderId, 2L)))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    static class StubGateway implements PaymentGatewayPort, PaymentGatewayFaultPort {

        Settings settings = new Settings(Mode.APPROVE, 0);
        int calls;
        // true 면 approve 가 타임아웃 (PG 는 pgLedger 에 결과를 나중에 남길 수 있다)
        boolean timeout;
        final Map<UUID, GatewayResult> pgLedger = new HashMap<>();
        final List<UUID> refunds = new ArrayList<>();

        @Override
        public GatewayResult approve(UUID orderId, long userId, long amount) {
            calls++;
            if (timeout) {
                throw new PaymentGatewayTimeoutException(orderId);
            }
            GatewayResult result = settings.mode() == Mode.DECLINE ? GatewayResult.declined("declined")
                    : GatewayResult.approved("tx-" + orderId);
            pgLedger.putIfAbsent(orderId, result);
            return pgLedger.get(orderId);
        }

        @Override
        public Optional<GatewayResult> inquire(UUID orderId) {
            return Optional.ofNullable(pgLedger.get(orderId));
        }

        @Override
        public void refund(UUID orderId) {
            refunds.add(orderId);
        }

        @Override
        public Settings current() {
            return settings;
        }

        @Override
        public void change(Settings settings) {
            this.settings = settings;
        }
    }

    static class InMemoryPayments implements PaymentPort {

        final Map<UUID, Payment> store = new HashMap<>();

        @Override
        public Optional<Payment> findByOrderId(UUID orderId) {
            return Optional.ofNullable(store.get(orderId)).map(PaymentServiceTest::copy);
        }

        @Override
        public Optional<Payment> findByOrderIdForUpdate(UUID orderId) {
            return findByOrderId(orderId);
        }

        @Override
        public List<UUID> findPendingOrderIds(int limit) {
            return store.values().stream()
                    .filter(p -> p.status() == PaymentStatus.UNKNOWN || p.needsRefund())
                    .map(Payment::orderId)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void insert(Payment payment) {
            if (store.putIfAbsent(payment.orderId(), copy(payment)) != null) {
                throw new IllegalStateException("duplicate payment");
            }
        }

        @Override
        public void update(Payment payment) {
            store.put(payment.orderId(), copy(payment));
        }
    }

    private static Payment copy(Payment p) {
        return Payment.restore(p.id(), p.orderId(), p.userId(), p.amount(), p.status(), p.transactionId(), p.reason(),
                p.cancelRequested(), p.createdAt(), p.updatedAt());
    }

    static class RecordingReplies implements SendPaymentReplyPort {

        final List<String> log = new ArrayList<>();

        @Override
        public void paymentApproved(UUID orderId, UUID paymentId) {
            log.add("approved:" + orderId + ":" + paymentId);
        }

        @Override
        public void paymentFailed(UUID orderId, String reason) {
            log.add("failed:" + orderId + ":" + reason);
        }

        @Override
        public void paymentCancelled(UUID orderId) {
            log.add("cancelled:" + orderId);
        }
    }
}
