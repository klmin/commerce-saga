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
import com.klmin.commerce.paymentservice.payment.domain.Money;
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

    static final UUID USER_1 = UUID.fromString("01900000-0000-7000-8001-000000000001");
    static final UUID USER_2 = UUID.fromString("01900000-0000-7000-8001-000000000002");

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final RecordingReplies replies = new RecordingReplies();
    private final StubGateway gateway = new StubGateway();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final InMemoryAttempts attempts = new InMemoryAttempts();
    private final PaymentService service = new PaymentService(
            new PaymentRecorder(payments, replies, processed, Clock.fixed(NOW, ZoneOffset.UTC)), gateway, gateway, payments,
            processed, attempts);

    /** PG 승인 호출 예약 (주문별 예약 수, 봉인) */
    static class InMemoryAttempts implements com.klmin.commerce.paymentservice.payment.application.port.out.PgApproveAttemptPort {
        final java.util.Map<UUID, Integer> calls = new java.util.HashMap<>();
        final java.util.Set<UUID> sealed = new java.util.HashSet<>();

        @Override
        public java.util.OptionalInt claim(UUID orderId) {
            if (sealed.contains(orderId)) {
                return java.util.OptionalInt.empty();
            }
            return java.util.OptionalInt.of(calls.merge(orderId, 1, Integer::sum));
        }

        @Override
        public boolean sealIfOnlyClaim(UUID orderId) {
            if (calls.getOrDefault(orderId, 0) == 1 && !sealed.contains(orderId)) {
                sealed.add(orderId);
                return true;
            }
            return false;
        }
    }

    @Test
    void pay_approved_recordsAndRepliesApproved() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        Payment payment = payments.store.get(orderId);
        assertThat(payment.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.id().version()).isEqualTo(7);
        assertThat(replies.log).containsExactly("approved:" + orderId + ":" + payment.id());
    }

    @Test
    void pay_circuitOpen_recordsDeclinedAndRepliesFailedAtOnce() {
        gateway.circuitOpen = true;
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(gateway.calls).isZero();
        assertThat(payments.store.get(orderId).status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payments.store.get(orderId).reason()).isEqualTo(PaymentService.CIRCUIT_OPEN_REASON);
        assertThat(replies.log).singleElement().satisfies(s -> assertThat(s).startsWith("failed:" + orderId));
    }

    @Test
    void pay_circuitOpen_afterOtherClaim_recordsUnknownWithoutReply() {
        // 다른 처리(재전달 전 시도, Saga 재전송, 다른 인스턴스의 동시 처리)가 PG 에 닿았을 수 있다
        UUID orderId = UUID.randomUUID();
        attempts.claim(orderId);
        gateway.circuitOpen = true;

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(gateway.calls).isZero();
        assertThat(attempts.sealed).doesNotContain(orderId);
        assertThat(payments.store.get(orderId).status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(replies.log).isEmpty();
    }

    @Test
    void pay_circuitOpen_seals_soLaterProcessingNeverCallsPg() {
        UUID orderId = UUID.randomUUID();
        gateway.circuitOpen = true;
        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));
        assertThat(attempts.sealed).contains(orderId);

        // 회로가 닫힌 다른 처리(다른 인스턴스·Saga 재전송)가 와도 PG 를 부르지 않고 같은 거절
        gateway.circuitOpen = false;
        payments.store.remove(orderId); // 앞선 처리의 기록이 아직 커밋되지 않은 경우
        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(gateway.calls).isZero();
        assertThat(payments.store.get(orderId).status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(payments.store.get(orderId).reason()).isEqualTo(PaymentService.CIRCUIT_OPEN_REASON);
    }

    @Test
    void pay_claimsBeforeCallingPg() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(attempts.calls).containsEntry(orderId, 1);
    }

    @Test
    void pay_declined_recordsAndRepliesFailed() {
        gateway.change(new Settings(Mode.DECLINE, 0));
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(payments.store.get(orderId).status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":declined");
    }

    @Test
    void duplicatePay_doesNotCallGatewayAgain_andRepliesSameResult() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));
        gateway.change(new Settings(Mode.DECLINE, 0));
        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(gateway.calls).isEqualTo(1);
        assertThat(replies.log).hasSize(2).allMatch(s -> s.startsWith("approved:"));
    }

    @Test
    void redeliveredMessage_isSkipped_withoutCallingGatewayOrReplying() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.pay(messageId, new PayCommand(orderId, USER_1, 5_000L));
        service.pay(messageId, new PayCommand(orderId, USER_1, 5_000L));

        assertThat(gateway.calls).isEqualTo(1);
        assertThat(replies.log).hasSize(1);
        assertThat(processed.ids).containsExactly(messageId);
    }

    @Test
    void redeliveredInvalidCommand_repliesOnce() {
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();

        service.pay(messageId, new PayCommand(orderId, USER_1, 0L));
        service.pay(messageId, new PayCommand(orderId, USER_1, 0L));

        assertThat(replies.log).hasSize(1);
    }

    @Test
    void invalidAmount_repliesFailedWithoutCallingGateway() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 0L));

        assertThat(gateway.calls).isZero();
        assertThat(payments.store).isEmpty();
        assertThat(replies.log).containsExactly("failed:" + orderId + ":invalid command: amount must be positive");
    }

    @Test
    void missingUserId_repliesFailedWithoutCallingGateway() {
        UUID orderId = UUID.randomUUID();

        service.pay(UUID.randomUUID(), new PayCommand(orderId, null, 5_000L));

        assertThat(gateway.calls).isZero();
        assertThat(payments.store).isEmpty();
        assertThat(replies.log).containsExactly("failed:" + orderId + ":invalid command: userId missing");
    }

    @Test
    void getPayment_onlyOwner() {
        UUID orderId = UUID.randomUUID();
        service.pay(UUID.randomUUID(), new PayCommand(orderId, USER_1, 5_000L));

        assertThat(service.getPayment(new GetPaymentQuery(orderId, USER_1)).amount()).isEqualTo(Money.of(5_000L));
        assertThatThrownBy(() -> service.getPayment(new GetPaymentQuery(orderId, USER_2)))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    static class StubGateway implements PaymentGatewayPort, PaymentGatewayFaultPort {

        Settings settings = new Settings(Mode.APPROVE, 0);
        int calls;
        // true 면 approve 가 타임아웃 (PG 는 pgLedger 에 결과를 나중에 남길 수 있다)
        boolean timeout;
        // true 면 서킷 브레이커가 열린 것처럼 PG 를 부르지 않는다
        boolean circuitOpen;
        final Map<UUID, GatewayResult> pgLedger = new HashMap<>();
        final List<UUID> refunds = new ArrayList<>();

        @Override
        public GatewayResult approve(UUID orderId, UUID userId, Money amount) {
            if (circuitOpen) {
                throw new com.klmin.commerce.paymentservice.payment.application.PaymentGatewayUnavailableException(orderId);
            }
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
