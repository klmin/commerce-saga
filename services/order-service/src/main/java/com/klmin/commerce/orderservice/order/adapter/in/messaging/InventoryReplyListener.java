package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleStockReplyUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * inventory.reply 소비. 처리 중 일시 오류는 복구될 때까지 재시도한다.
 * 재시도해도 같은 오류(본문·헤더 이상)는 NonRetryableMessageException 으로 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 멱등 처리 이력 테이블은 3단계. 지금은 도메인 상태 전이가 PENDING 에서만 일어나서 중복 응답이 무해하다
 */
@Component
class InventoryReplyListener {

    private final HandleStockReplyUseCase handleStockReplyUseCase;
    private final JsonMapper json;

    InventoryReplyListener(HandleStockReplyUseCase handleStockReplyUseCase, JsonMapper json) {
        this.handleStockReplyUseCase = handleStockReplyUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.INVENTORY_REPLY)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = header(record, MessageHeaders.TYPE);
        // 응답 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        if (type == null) {
            throw new NonRetryableMessageException("type header missing: " + describe(record));
        }
        switch (type) {
            case StockReserved.TYPE -> {
                StockReserved reply = read(record, StockReserved.class);
                handleStockReplyUseCase.stockReserved(requireOrderId(reply.orderId(), record));
            }
            case StockReservationFailed.TYPE -> {
                StockReservationFailed reply = read(record, StockReservationFailed.class);
                handleStockReplyUseCase.stockReservationFailed(requireOrderId(reply.orderId(), record),
                        reply.reason());
            }
            default -> throw new NonRetryableMessageException("unknown type " + type + ": " + describe(record));
        }
    }

    // 본문이 비었거나 JSON null 이면 재시도해도 같으므로 재시도 불가 오류로 분류한다
    private <T> T read(ConsumerRecord<String, String> record, Class<T> type) {
        T value = record.value() == null ? null : json.readValue(record.value(), type);
        if (value == null) {
            throw new NonRetryableMessageException("empty payload: " + describe(record));
        }
        return value;
    }

    private static UUID requireOrderId(UUID orderId, ConsumerRecord<?, ?> record) {
        if (orderId == null) {
            throw new NonRetryableMessageException("orderId missing: " + describe(record));
        }
        return orderId;
    }

    private static String describe(ConsumerRecord<?, ?> record) {
        return "topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
