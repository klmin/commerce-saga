package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ConfirmStockUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * order.events 구독 (코레오그래피). OrderCompleted 를 받으면 잡아 둔 재고를 판매로 확정한다.
 * <p>
 * 이벤트 토픽은 여러 소비자가 보는 공용 토픽이라 커맨드 토픽과 달리 관심 없는 타입(OrderCancelled 등,
 * 나중에 추가될 타입 포함)은 조용히 건너뛴다. type 헤더가 없거나 관심 있는 타입의 본문이 잘못되면 DLT 로 보존한다.
 */
@Component
class OrderEventsListener {

    private final ConfirmStockUseCase confirmStockUseCase;
    private final JsonMapper json;

    OrderEventsListener(ConfirmStockUseCase confirmStockUseCase, JsonMapper json) {
        this.confirmStockUseCase = confirmStockUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = Messages.type(record);
        // 관심 없는 타입은 건너뛰지만, type 헤더 자체가 없거나 비었으면 메시지 이상이라 DLT 로 보존한다
        if (type == null || type.isBlank()) {
            throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
        }
        if (!OrderCompleted.TYPE.equals(type)) {
            return;
        }
        OrderCompleted event = Messages.read(json, record, OrderCompleted.class);
        confirmStockUseCase.confirm(Messages.messageId(record), Messages.requireOrderId(event.orderId(), record));
    }
}
