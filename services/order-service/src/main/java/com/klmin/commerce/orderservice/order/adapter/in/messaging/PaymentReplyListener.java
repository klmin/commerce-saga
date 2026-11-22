package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.PaymentApproved;
import com.klmin.commerce.contract.payment.PaymentFailed;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** payment.reply 소비. 오류 처리는 InventoryReplyListener 와 같다 */
@Component
class PaymentReplyListener {

    private final HandleSagaReplyUseCase handleSagaReplyUseCase;
    private final JsonMapper json;

    PaymentReplyListener(HandleSagaReplyUseCase handleSagaReplyUseCase, JsonMapper json) {
        this.handleSagaReplyUseCase = handleSagaReplyUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PAYMENT_REPLY)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = Messages.type(record);
        SagaReplyCommand reply = switch (type) {
            case PaymentApproved.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, PaymentApproved.class).orderId(), record),
                    SagaEventType.PAYMENT_APPROVED);
            case PaymentFailed.TYPE -> {
                PaymentFailed m = Messages.read(json, record, PaymentFailed.class);
                yield new SagaReplyCommand(Messages.requireOrderId(m.orderId(), record),
                        SagaEventType.PAYMENT_FAILED, m.reason());
            }
            case null -> throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
            default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        };
        handleSagaReplyUseCase.handle(Messages.messageId(record), reply);
    }
}
