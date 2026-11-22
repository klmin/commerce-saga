package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentPort;
import com.klmin.commerce.paymentservice.payment.application.port.out.SendPaymentReplyPort;
import com.klmin.commerce.paymentservice.payment.domain.Payment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 결제 결과 기록 + 응답(outbox) 트랜잭션. PG 호출은 이 트랜잭션 밖(PaymentService)에서 한다
 * (외부 호출 동안 DB 커넥션·락을 잡고 있지 않게).
 */
@Component
class PaymentRecorder {

    private final PaymentPort paymentPort;
    private final SendPaymentReplyPort replyPort;
    private final Clock clock;

    PaymentRecorder(PaymentPort paymentPort, SendPaymentReplyPort replyPort, Clock clock) {
        this.paymentPort = paymentPort;
        this.replyPort = replyPort;
        this.clock = clock;
    }

    /** 이미 처리한 주문이면 기록된 결과를 다시 응답하고 true */
    @Transactional
    public boolean replyIfAlreadyProcessed(UUID orderId) {
        return paymentPort.findByOrderId(orderId)
                .map(payment -> {
                    reply(payment);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void record(PayCommand command, GatewayResult result) {
        Payment payment = result.approved()
                ? Payment.approved(UUID.randomUUID(), command.orderId(), command.userId(), command.amount(),
                        result.transactionId(), clock.instant())
                : Payment.declined(UUID.randomUUID(), command.orderId(), command.userId(), command.amount(),
                        result.reason(), clock.instant());
        paymentPort.insert(payment);
        reply(payment);
    }

    /** 기록하지 않는 실패 응답 (잘못된 커맨드). 다시 와도 같은 이유로 실패한다 */
    @Transactional
    public void replyInvalid(UUID orderId, String reason) {
        replyPort.paymentFailed(orderId, reason);
    }

    private void reply(Payment payment) {
        if (payment.isApproved()) {
            replyPort.paymentApproved(payment.orderId(), payment.id());
        } else {
            replyPort.paymentFailed(payment.orderId(), payment.reason());
        }
    }
}
