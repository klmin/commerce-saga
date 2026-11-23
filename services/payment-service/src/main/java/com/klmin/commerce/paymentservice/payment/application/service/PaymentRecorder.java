package com.klmin.commerce.paymentservice.payment.application.service;

import com.github.f4b6a3.uuid.UuidCreator;
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
import java.util.UUID;

/**
 * 결제 결과 기록 + 메시지 처리 기록 + 응답(outbox) 트랜잭션. PG 호출은 이 트랜잭션 밖(PaymentService)에서 한다
 * (외부 호출 동안 DB 커넥션·락을 잡고 있지 않게).
 * <p>
 * 각 트랜잭션은 processed_message 기록(markProcessed)과 함께 커밋된다. 같은 메시지가 동시에 두 번 처리되면
 * 늦은 쪽은 기록에 실패해 아무것도 바꾸지 않는다 (PG 는 orderId 로 멱등이라 두 번 승인되지 않는다).
 */
@Component
class PaymentRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecorder.class);

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

    /** 다른 메시지로 이미 처리한 주문이면 기록된 결과를 다시 응답하고 true */
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
        Payment payment = result.approved()
                ? Payment.approved(UuidCreator.getTimeOrderedEpoch(), command.orderId(), command.userId(), command.amount(),
                        result.transactionId(), clock.instant())
                : Payment.declined(UuidCreator.getTimeOrderedEpoch(), command.orderId(), command.userId(), command.amount(),
                        result.reason(), clock.instant());
        paymentPort.insert(payment);
        reply(payment);
    }

    /** 기록하지 않는 실패 응답 (잘못된 커맨드). 다시 와도 같은 이유로 실패한다 */
    @Transactional
    public void replyInvalid(UUID messageId, UUID orderId, String reason) {
        if (markProcessed(messageId, orderId)) {
            replyPort.paymentFailed(orderId, reason);
        }
    }

    private boolean markProcessed(UUID messageId, UUID orderId) {
        if (processedMessagePort.markProcessed(messageId)) {
            return true;
        }
        log.info("이미 처리한 메시지 건너뜀: Pay (messageId={}, orderId={})", messageId, orderId);
        return false;
    }

    private void reply(Payment payment) {
        if (payment.isApproved()) {
            replyPort.paymentApproved(payment.orderId(), payment.id());
        } else {
            replyPort.paymentFailed(payment.orderId(), payment.reason());
        }
    }
}
