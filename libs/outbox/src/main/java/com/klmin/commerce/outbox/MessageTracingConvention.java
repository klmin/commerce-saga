package com.klmin.commerce.outbox;

import io.micrometer.common.KeyValues;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation.DefaultKafkaListenerObservationConvention;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import java.nio.charset.StandardCharsets;

/**
 * 리스너 처리 span 에 메시지 키(orderId)와 타입(헤더 {@code type})을 단다.
 * Tempo 에서 {@code messaging.kafka.message.key = <orderId>} 로 주문 한 건의 trace 를 찾을 수 있다 (ADR 0013).
 * high cardinality 값이라 span 에만 붙고 메트릭 태그에는 들어가지 않는다.
 */
public class MessageTracingConvention extends DefaultKafkaListenerObservationConvention {

    static final String MESSAGE_KEY = "messaging.kafka.message.key";
    static final String MESSAGE_TYPE = "messaging.message.type";

    @Override
    public KeyValues getHighCardinalityKeyValues(KafkaRecordReceiverContext context) {
        KeyValues values = super.getHighCardinalityKeyValues(context);
        ConsumerRecord<?, ?> record = context.getRecord();
        if (record == null) {
            return values;
        }
        if (record.key() != null) {
            values = values.and(MESSAGE_KEY, record.key().toString());
        }
        Header type = record.headers().lastHeader("type");
        if (type != null && type.value() != null) {
            values = values.and(MESSAGE_TYPE, new String(type.value(), StandardCharsets.UTF_8));
        }
        return values;
    }
}
