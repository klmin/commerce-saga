package com.klmin.commerce.tools.dlt;

import com.klmin.commerce.contract.MessageHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * DLT 에 보존된 메시지 하나. Spring Kafka DeadLetterPublishingRecoverer 는 원본 key·value·헤더를 그대로 두고
 * {@code kafka_dlt-*} 헤더(원래 토픽·파티션·오프셋, 예외 클래스·메시지)를 덧붙인다.
 */
record DltRecord(String dltTopic, int partition, long offset, String key, String value, Headers headers) {

    static final String DLT_SUFFIX = ".DLT";
    static final String DLT_HEADER_PREFIX = "kafka_dlt-";
    static final String ORIGINAL_TOPIC = "kafka_dlt-original-topic";
    static final String ORIGINAL_PARTITION = "kafka_dlt-original-partition";
    static final String ORIGINAL_OFFSET = "kafka_dlt-original-offset";
    static final String EXCEPTION_FQCN = "kafka_dlt-exception-fqcn";
    static final String EXCEPTION_CAUSE_FQCN = "kafka_dlt-exception-cause-fqcn";
    static final String EXCEPTION_MESSAGE = "kafka_dlt-exception-message";

    /** 재처리한 메시지에 남기는 출처 (어느 DLT 의 몇 번째 메시지를 다시 보냈는지). 컨슈머는 모르는 헤더를 무시한다 */
    static final String REPLAYED_FROM = "x-dlt-replayed-from";

    static DltRecord from(ConsumerRecord<String, String> record) {
        return new DltRecord(record.topic(), record.partition(), record.offset(), record.key(), record.value(),
                record.headers());
    }

    String position() {
        return partition + ":" + offset;
    }

    String type() {
        return text(MessageHeaders.TYPE);
    }

    String messageId() {
        return text(MessageHeaders.ID);
    }

    String error() {
        String fqcn = text(EXCEPTION_CAUSE_FQCN) != null ? text(EXCEPTION_CAUSE_FQCN) : text(EXCEPTION_FQCN);
        String message = text(EXCEPTION_MESSAGE);
        // 예외 메시지가 여러 줄이면 목록이 흐트러지므로 한 줄로
        return fqcn + ": " + (message == null ? null : message.replaceAll("\\s*\\R\\s*", " "));
    }

    /** 원래 토픽 위치 (예: inventory.command/1@42). 헤더가 없으면 DLT 이름에서 추정한 토픽만 */
    String origin() {
        Header partitionHeader = headers.lastHeader(ORIGINAL_PARTITION);
        Header offsetHeader = headers.lastHeader(ORIGINAL_OFFSET);
        String topic = originalTopic();
        if (partitionHeader == null || offsetHeader == null
                || partitionHeader.value().length != Integer.BYTES || offsetHeader.value().length != Long.BYTES) {
            return topic;
        }
        return topic + "/" + ByteBuffer.wrap(partitionHeader.value()).getInt()
                + "@" + ByteBuffer.wrap(offsetHeader.value()).getLong();
    }

    /** 다시 보낼 토픽. kafka_dlt-original-topic 헤더, 없으면 DLT 이름에서 .DLT 를 뗀다 */
    String originalTopic() {
        String header = text(ORIGINAL_TOPIC);
        if (header != null && !header.isBlank()) {
            return header;
        }
        return dltTopic.endsWith(DLT_SUFFIX) ? dltTopic.substring(0, dltTopic.length() - DLT_SUFFIX.length()) : null;
    }

    /**
     * 원래 토픽으로 다시 보낼 메시지. key·value 와 원본 헤더(id, type 등)는 그대로 두고 kafka_dlt-* 헤더만 뺀다.
     * id 가 그대로라, 이미 처리된 메시지가 섞여 있어도 컨슈머의 processed_message 가 두 번 반영을 막는다 (ADR 0006).
     */
    ProducerRecord<String, String> toReplay() {
        String target = originalTopic();
        if (target == null || target.isBlank() || target.endsWith(DLT_SUFFIX)) {
            throw new IllegalStateException("cannot resolve original topic for " + dltTopic + " " + position());
        }
        RecordHeaders replayHeaders = new RecordHeaders();
        for (Header header : headers) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX) && !header.key().equals(REPLAYED_FROM)) {
                replayHeaders.add(header.key(), header.value());
            }
        }
        replayHeaders.add(REPLAYED_FROM, (dltTopic + ":" + position()).getBytes(StandardCharsets.UTF_8));
        return new ProducerRecord<>(target, null, key, value, replayHeaders);
    }

    private String text(String name) {
        Header header = headers.lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
