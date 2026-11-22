package com.klmin.commerce.paymentservice.payment.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.PaymentApproved;
import com.klmin.commerce.contract.payment.PaymentFailed;
import com.klmin.commerce.outbox.OutboxWriter;
import com.klmin.commerce.paymentservice.payment.application.port.out.SendPaymentReplyPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 결제 응답을 outbox 에 저장한다. 메시지 키 = orderId */
@Component
class PaymentReplyOutboxAdapter implements SendPaymentReplyPort {

    private final OutboxWriter outboxWriter;

    PaymentReplyOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void paymentApproved(UUID orderId, UUID paymentId) {
        outboxWriter.append(Topics.PAYMENT_REPLY, orderId.toString(), PaymentApproved.TYPE,
                new PaymentApproved(orderId, paymentId));
    }

    @Override
    public void paymentFailed(UUID orderId, String reason) {
        outboxWriter.append(Topics.PAYMENT_REPLY, orderId.toString(), PaymentFailed.TYPE,
                new PaymentFailed(orderId, reason));
    }
}
