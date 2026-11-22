package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleStockReplyUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * inventory.reply 소비. 처리 중 예외가 나면 Spring Kafka 기본 에러 핸들러가 재시도한다.
 * (멱등 처리 이력 테이블, retry 토픽·DLQ 는 3단계. 지금은 도메인 상태 전이가 PENDING 에서만 일어나서 중복 응답이 무해하다)
 */
@Component
class InventoryReplyListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryReplyListener.class);

    private final HandleStockReplyUseCase handleStockReplyUseCase;
    private final JsonMapper json;

    InventoryReplyListener(HandleStockReplyUseCase handleStockReplyUseCase, JsonMapper json) {
        this.handleStockReplyUseCase = handleStockReplyUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.INVENTORY_REPLY)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = header(record, MessageHeaders.TYPE);
        if (type == null) {
            log.warn("type 헤더 없는 메시지 무시 (topic={}, partition={}, offset={})",
                    record.topic(), record.partition(), record.offset());
            return;
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
            default -> log.warn("알 수 없는 메시지 타입 무시: {} (topic={}, offset={})",
                    type, record.topic(), record.offset());
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
