package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUseFailed;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** point.reply 소비. 오류 처리는 InventoryReplyListener 와 같다 */
@Component
class PointReplyListener {

    private final HandleSagaReplyUseCase handleSagaReplyUseCase;
    private final JsonMapper json;

    PointReplyListener(HandleSagaReplyUseCase handleSagaReplyUseCase, JsonMapper json) {
        this.handleSagaReplyUseCase = handleSagaReplyUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.POINT_REPLY)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = Messages.type(record);
        SagaReplyCommand reply = switch (type) {
            case PointUsed.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, PointUsed.class).orderId(), record), SagaEventType.POINT_USED);
            case PointUseFailed.TYPE -> {
                PointUseFailed m = Messages.read(json, record, PointUseFailed.class);
                yield new SagaReplyCommand(Messages.requireOrderId(m.orderId(), record),
                        SagaEventType.POINT_USE_FAILED, m.reason());
            }
            case PointRefunded.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, PointRefunded.class).orderId(), record), SagaEventType.POINT_REFUNDED);
            case null -> throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
            default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        };
        handleSagaReplyUseCase.handle(reply);
    }
}
