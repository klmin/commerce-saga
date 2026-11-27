package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.inventory.StockReturned;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * inventory.reply 소비. 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 같은 메시지의 재전달은 processed_message(id 헤더)로 건너뛰고, 중복·늦은 응답은 Saga 상태 머신이 무시한다.
 */
@Component
class InventoryReplyListener {

    private final HandleSagaReplyUseCase handleSagaReplyUseCase;
    private final JsonMapper json;

    InventoryReplyListener(HandleSagaReplyUseCase handleSagaReplyUseCase, JsonMapper json) {
        this.handleSagaReplyUseCase = handleSagaReplyUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.INVENTORY_REPLY)
    void onMessage(ConsumerRecord<String, String> record) {
        // 응답 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        String type = Messages.type(record);
        SagaReplyCommand reply = switch (type) {
            case StockReserved.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, StockReserved.class).orderId(), record), SagaEventType.STOCK_RESERVED);
            case StockReservationFailed.TYPE -> {
                StockReservationFailed m = Messages.read(json, record, StockReservationFailed.class);
                yield new SagaReplyCommand(Messages.requireOrderId(m.orderId(), record),
                        SagaEventType.STOCK_RESERVATION_FAILED, m.reason());
            }
            case StockReleased.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, StockReleased.class).orderId(), record), SagaEventType.STOCK_RELEASED);
            case StockReturned.TYPE -> SagaReplyCommand.of(Messages.requireOrderId(
                    Messages.read(json, record, StockReturned.class).orderId(), record), SagaEventType.STOCK_RETURNED);
            case null -> throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
            default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        };
        handleSagaReplyUseCase.handle(Messages.messageId(record), reply);
    }
}
