package com.klmin.commerce.searchservice.product.adapter.in.messaging;

import com.klmin.commerce.searchservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.searchservice.product.application.port.in.IndexProductCommand;
import com.klmin.commerce.searchservice.product.application.port.in.IndexProductUseCase;
import com.klmin.commerce.searchservice.product.application.port.in.RemoveProductCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * product.cdc.products 구독 (ADR 0010, 토픽 계약은 docs/scenarios/16-product-cdc.md).
 * 값은 products 의 변경 후 행(평평한 JSON), 키는 상품 id(UUID 문자열), 헤더 {@code __lsn}(원본 WAL 위치 → 색인 외부 버전),
 * {@code __source_ts_ms}(원본 커밋 시각 → 색인 지연 지표). 값이 null 이면 tombstone(행 삭제).
 * <p>
 * 커맨드·이벤트 토픽과 달리 {@code type} 헤더가 없다(비즈니스 코드가 발행하지 않는 테이블 복제). 본문·키·헤더가 계약과
 * 맞지 않으면 재시도하지 않고 DLT 로 보존한다. 검색 저장소 장애는 복구될 때까지 제자리 재시도(ADR 0012)
 */
@Component
class ProductCdcListener {

    static final String TOPIC = "product.cdc.products";
    static final String LSN_HEADER = "__lsn";
    static final String SOURCE_TS_HEADER = "__source_ts_ms";

    private final IndexProductUseCase indexProductUseCase;
    private final JsonMapper json;
    private final Clock clock;
    private final AtomicLong lagMillis = new AtomicLong();
    private final Counter applied;
    private final Counter skipped;

    ProductCdcListener(IndexProductUseCase indexProductUseCase, JsonMapper json, Clock clock, MeterRegistry meters) {
        this.indexProductUseCase = indexProductUseCase;
        this.json = json;
        this.clock = clock;
        // 마지막으로 처리한 변경이 원본 커밋에서 색인까지 걸린 시간. 소비자가 멈추면 값이 그대로라 lag 경보(kafka-exporter)와 같이 본다
        Gauge.builder("search_indexing_lag_seconds", lagMillis, v -> v.get() / 1000.0)
                .description("원본 커밋(products) → 검색 색인 반영까지 걸린 시간, 마지막 메시지 기준")
                .register(meters);
        this.applied = Counter.builder("search_indexed_total").tag("result", "applied")
                .description("검색 색인 반영 수 (applied: 반영, skipped: 같은·옛 버전이라 건너뜀)").register(meters);
        this.skipped = Counter.builder("search_indexed_total").tag("result", "skipped").register(meters);
    }

    @KafkaListener(topics = TOPIC)
    void onMessage(ConsumerRecord<String, String> record) {
        UUID productId = productId(record.key());
        Long version = longHeader(record, LSN_HEADER);
        boolean done;
        if (record.value() == null) {
            done = indexProductUseCase.remove(new RemoveProductCommand(productId, version));
        } else {
            if (version == null) {
                throw new NonRetryableMessageException(LSN_HEADER + " header missing: " + describe(record));
            }
            done = indexProductUseCase.index(toCommand(record, productId, version));
        }
        (done ? applied : skipped).increment();
        Long sourceTs = longHeader(record, SOURCE_TS_HEADER);
        if (sourceTs != null) {
            lagMillis.set(Math.max(0, clock.millis() - sourceTs));
        }
    }

    private IndexProductCommand toCommand(ConsumerRecord<String, String> record, UUID productId, long version) {
        JsonNode row = json.readTree(record.value());
        UUID id = requireUuid(row, "id", record);
        if (!id.equals(productId)) {
            throw new NonRetryableMessageException("key " + productId + " != id " + id + ": " + describe(record));
        }
        return new IndexProductCommand(id, requireText(row, "name", record), optionalText(row, "description"),
                requireLong(row, "price", record), requireBoolean(row, "on_sale", record),
                requireInstant(row, "updated_at", record), version);
    }

    private static UUID productId(String key) {
        UUID id = parseUuid(key);
        if (id == null) {
            throw new NonRetryableMessageException("key is not a product id: " + key);
        }
        return id;
    }

    /** 표준 표기(8-4-4-4-12)만 받는다. UUID.fromString 은 "1-1-1-1-1" 같은 표기도 받아 준다 */
    private static UUID parseUuid(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long longHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new NonRetryableMessageException(name + " header is not a number: " + value);
        }
    }

    private static long requireLong(JsonNode row, String field, ConsumerRecord<String, String> record) {
        JsonNode node = row.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new NonRetryableMessageException(field + " missing or not a number: " + describe(record));
        }
        return node.longValue();
    }

    private static UUID requireUuid(JsonNode row, String field, ConsumerRecord<String, String> record) {
        UUID id = parseUuid(requireText(row, field, record));
        if (id == null) {
            throw new NonRetryableMessageException(field + " is not a uuid: " + describe(record));
        }
        return id;
    }

    private static String requireText(JsonNode row, String field, ConsumerRecord<String, String> record) {
        JsonNode node = row.get(field);
        if (node == null || !node.isString()) {
            throw new NonRetryableMessageException(field + " missing: " + describe(record));
        }
        return node.stringValue();
    }

    private static String optionalText(JsonNode row, String field) {
        JsonNode node = row.get(field);
        return node == null || node.isNull() ? "" : node.asString();
    }

    private static boolean requireBoolean(JsonNode row, String field, ConsumerRecord<String, String> record) {
        JsonNode node = row.get(field);
        if (node == null || !node.isBoolean()) {
            throw new NonRetryableMessageException(field + " missing: " + describe(record));
        }
        return node.booleanValue();
    }

    private static Instant requireInstant(JsonNode row, String field, ConsumerRecord<String, String> record) {
        String text = requireText(row, field, record);
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new NonRetryableMessageException(field + " is not an instant: " + text);
        }
    }

    private static String describe(ConsumerRecord<String, String> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset() + " key=" + record.key();
    }
}
