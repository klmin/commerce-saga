package com.klmin.commerce.paymentservice.payment.application.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.ProcessedMessagePort;
import com.klmin.commerce.paymentservice.payment.application.port.out.SendPaymentReplyPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 결과 기록 + 메시지 처리 기록 + 응답(outbox) 트랜잭션. PG 호출(승인·조회·환불)은 이 트랜잭션 밖에서 한다
 * (외부 호출 동안 DB 커넥션·락을 잡고 있지 않게).
 * <p>
 * 메시지 처리 트랜잭션은 processed_message 기록(markProcessed)과 함께 커밋된다. 같은 메시지가 동시에 두 번 처리되면
 * 늦은 쪽은 기록에 실패해 아무것도 바꾸지 않는다 (PG 는 orderId 로 멱등이라 두 번 승인되지 않는다).
 * 이미 있는 결제를 바꾸는 트랜잭션(취소 요청, PG 조회 결과, 환불 완료)은 결제 행을 잠그고 한다.
 */
@Component
class PaymentRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecorder.class);
    static final String CANCELLED_REASON = "payment cancelled";

    private final PaymentPort paymentPort;
    private final SendPaymentReplyPort replyPort;
    private final ProcessedMessagePort processedMessagePort;
    private final Clock clock;

    PaymentRecorder(PaymentPort paymentPort, SendPaymentReplyPort replyPort,
                    ProcessedMessagePort processedMessagePort, Clock clock) {
        this.paymentPort = paymentPort;
        this.replyPort = replyPort;
        this.processedMessagePort = processedMessagePort;
        this.clock = clock;
    }

    /**
     * 다른 메시지로 이미 처리한 주문이면 기록된 결과를 다시 응답하고 true.
     * PG 결과를 아직 모르면(UNKNOWN) 응답하지 않는다. PG 조회로 확정될 때 응답이 나간다
     */
    @Transactional
    public boolean replyIfAlreadyProcessed(UUID messageId, UUID orderId) {
        return paymentPort.findByOrderId(orderId)
                .map(payment -> {
                    if (markProcessed(messageId, orderId)) {
                        reply(payment);
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void record(UUID messageId, PayCommand command, GatewayResult result) {
        if (!markProcessed(messageId, command.orderId())) {
            return;
        }
        Instant now = clock.instant();
        Payment payment = result.approved()
                ? Payment.approved(UuidCreator.getTimeOrderedEpoch(), command.orderId(), command.userId(), command.amount(),
                        result.transactionId(), now)
                : Payment.declined(UuidCreator.getTimeOrderedEpoch(), command.orderId(), command.userId(), command.amount(),
                        result.reason(), now);
        paymentPort.insert(payment);
        reply(payment);
    }

    /** PG 응답 타임아웃: 결과를 모르므로 실패로 응답하지 않고 UNKNOWN 으로 기록한다 (PG 조회로 확정) */
    @Transactional
    public void recordUnknown(UUID messageId, PayCommand command) {
        if (!markProcessed(messageId, command.orderId())) {
            return;
        }
        paymentPort.insert(Payment.unknown(UuidCreator.getTimeOrderedEpoch(), command.orderId(), command.userId(),
                command.amount(), clock.instant()));
        log.warn("PG 응답 타임아웃, 결과 조회로 확정 예정 (orderId={})", command.orderId());
    }

    /** 기록하지 않는 실패 응답 (잘못된 커맨드). 다시 와도 같은 이유로 실패한다 */
    @Transactional
    public void replyInvalid(UUID messageId, UUID orderId, String reason) {
        if (markProcessed(messageId, orderId)) {
            replyPort.paymentFailed(orderId, reason);
        }
    }

    /**
     * 주문 쪽 결제 취소: 취소 요청을 기록하고 바로 응답한다. 환불이 필요하면(승인 상태, 또는 나중에 승인 확인)
     * 보정 작업이 PG 환불까지 한다. 결제 기록이 없으면 취소 기록을 남겨 늦게 온 Pay 가 결제하지 않게 한다
     */
    @Transactional
    public void cancel(UUID messageId, CancelPaymentCommand command) {
        if (!markProcessed(messageId, command.orderId())) {
            return;
        }
        Instant now = clock.instant();
        Optional<Payment> found = paymentPort.findByOrderIdForUpdate(command.orderId());
        if (found.isEmpty()) {
            log.warn("결제 기록 없는 결제 취소 (orderId={})", command.orderId());
            paymentPort.insert(Payment.cancelledBeforePayment(UuidCreator.getTimeOrderedEpoch(), command.orderId(),
                    command.userId(), command.amount(), now));
        } else {
            Payment payment = found.get();
            if (payment.requestCancel(now)) {
                paymentPort.update(payment);
                log.warn("결제 취소 요청 기록 (orderId={}, status={}, 환불 필요={})",
                        command.orderId(), payment.status(), payment.needsRefund());
            }
        }
        // 보상은 결과가 하나뿐이다. 환불이 남았어도 결제 서비스가 책임지므로 Saga 는 다음 보상으로 간다
        replyPort.paymentCancelled(command.orderId());
    }

    /**
     * PG 조회 결과로 UNKNOWN 을 확정한다. 취소 요청이 없으면 그때 결과를 응답하고(Saga 진행),
     * 있으면 응답하지 않는다(Saga 는 이미 보상 중, 승인이면 환불 대상)
     */
    @Transactional
    public void resolve(UUID orderId, GatewayResult result) {
        Optional<Payment> found = paymentPort.findByOrderIdForUpdate(orderId);
        if (found.isEmpty()) {
            return;
        }
        Payment payment = found.get();
        if (!payment.resolve(result.approved(), result.transactionId(), result.reason(), clock.instant())) {
            return;
        }
        paymentPort.update(payment);
        log.info("PG 조회로 결제 확정: {} (orderId={}, 취소 요청={})", payment.status(), orderId,
                payment.cancelRequested());
        if (!payment.cancelRequested()) {
            reply(payment);
        }
    }

    /** PG 환불이 끝났다 */
    @Transactional
    public void markRefunded(UUID orderId) {
        paymentPort.findByOrderIdForUpdate(orderId).ifPresent(payment -> {
            if (payment.refunded(clock.instant())) {
                paymentPort.update(payment);
                log.warn("취소된 주문의 승인 결제 환불 완료 (orderId={}, transactionId={})",
                        orderId, payment.transactionId());
            }
        });
    }

    private boolean markProcessed(UUID messageId, UUID orderId) {
        if (processedMessagePort.markProcessed(messageId)) {
            return true;
        }
        log.info("이미 처리한 메시지 건너뜀 (messageId={}, orderId={})", messageId, orderId);
        return false;
    }

    private void reply(Payment payment) {
        switch (payment.status()) {
            case APPROVED -> replyPort.paymentApproved(payment.orderId(), payment.id());
            case DECLINED -> replyPort.paymentFailed(payment.orderId(), payment.reason());
            case CANCELLED, REFUNDED -> replyPort.paymentFailed(payment.orderId(), CANCELLED_REASON);
            case UNKNOWN -> {
                // PG 조회로 확정될 때 응답한다
            }
        }
    }
}
