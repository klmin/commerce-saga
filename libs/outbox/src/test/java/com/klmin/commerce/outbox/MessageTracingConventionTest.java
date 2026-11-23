package com.klmin.commerce.outbox;

import io.micrometer.common.KeyValue;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTracingConventionTest {

    private final MessageTracingConvention convention = new MessageTracingConvention();

    @Test
    void 메시지_키와_타입을_span_에_단다() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("inventory.command", 0, 7L, "order-1", "{}");
        record.headers().add("type", "ReserveStock".getBytes(StandardCharsets.UTF_8));

        var values = convention.getHighCardinalityKeyValues(context(record));

        assertThat(values.stream().map(KeyValue::getKey)).contains(MessageTracingConvention.MESSAGE_KEY,
                MessageTracingConvention.MESSAGE_TYPE);
        assertThat(values.stream()).contains(KeyValue.of(MessageTracingConvention.MESSAGE_KEY, "order-1"),
                KeyValue.of(MessageTracingConvention.MESSAGE_TYPE, "ReserveStock"));
    }

    @Test
    void 키나_타입이_없으면_붙이지_않는다() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("inventory.command", 0, 7L, null, "{}");

        var values = convention.getHighCardinalityKeyValues(context(record));

        assertThat(values.stream().map(KeyValue::getKey)).doesNotContain(MessageTracingConvention.MESSAGE_KEY,
                MessageTracingConvention.MESSAGE_TYPE);
    }

    private static KafkaRecordReceiverContext context(ConsumerRecord<String, String> record) {
        return new KafkaRecordReceiverContext(record, "listener-1", "client-1", "group-1", () -> "cluster");
    }
}
