package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 리스너 공통: 헤더 읽기, 본문 역직렬화, 필수 값 검사. 영구 오류는 NonRetryableMessageException (DLT 행) */
final class Messages {

    private Messages() {
    }

    /** type 헤더. 없거나 값이 null 이면 null */
    static String type(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(MessageHeaders.TYPE);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * id 헤더(메시지 식별자 = 보낸 쪽 outbox.id). 멱등 처리의 기준이라 없거나 UUID 가 아니면
     * 재시도해도 같으므로 재시도 불가 오류 (DLT 에 보존)
     */
    static UUID messageId(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(MessageHeaders.ID);
        if (header == null || header.value() == null) {
            throw new NonRetryableMessageException("id header missing: " + describe(record));
        }
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new NonRetryableMessageException("invalid id header: " + describe(record));
        }
    }

    /** 본문이 비었거나 JSON null 이면 재시도해도 같으므로 재시도 불가 오류 */
    static <T> T read(JsonMapper json, ConsumerRecord<String, String> record, Class<T> type) {
        T value = record.value() == null ? null : json.readValue(record.value(), type);
        if (value == null) {
            throw new NonRetryableMessageException("empty payload: " + describe(record));
        }
        return value;
    }

    static UUID requireOrderId(UUID orderId, ConsumerRecord<?, ?> record) {
        if (orderId == null) {
            throw new NonRetryableMessageException("orderId missing: " + describe(record));
        }
        return orderId;
    }

    static String describe(ConsumerRecord<?, ?> record) {
        return "topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset();
    }
}
