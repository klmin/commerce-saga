package com.klmin.commerce.searchservice.product.adapter.in.messaging;

import com.klmin.commerce.searchservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.searchservice.product.application.port.in.IndexProductCommand;
import com.klmin.commerce.searchservice.product.application.port.in.IndexProductUseCase;
import com.klmin.commerce.searchservice.product.application.port.in.RemoveProductCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CDC 메시지(토픽 계약, docs/scenarios/16-product-cdc.md) → 색인 커맨드 */
class ProductCdcListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-25T05:00:10Z");
    private static final String K4 = "01900000-0000-7000-8000-000000000004";
    private static final String K5 = "01900000-0000-7000-8000-000000000005";
    private static final String ROW = """
            {"id":"01900000-0000-7000-8000-000000000004","name":"한정판 키캡","description":"재고 1개","price":12100,"on_sale":true,"updated_at":"2026-11-25T05:00:00.123456Z"}""";

    private final RecordingUseCase useCase = new RecordingUseCase();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProductCdcListener listener = new ProductCdcListener(useCase, JsonMapper.builder().build(),
            Clock.fixed(NOW, ZoneOffset.UTC), meters);

    private static ConsumerRecord<String, String> record(String key, String value, String lsn, String sourceTs) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(ProductCdcListener.TOPIC, 0, 7L, key, value);
        if (lsn != null) {
            r.headers().add(new RecordHeader(ProductCdcListener.LSN_HEADER, lsn.getBytes(StandardCharsets.UTF_8)));
        }
        if (sourceTs != null) {
            r.headers().add(new RecordHeader(ProductCdcListener.SOURCE_TS_HEADER, sourceTs.getBytes(StandardCharsets.UTF_8)));
        }
        return r;
    }

    @Test
    void row_becomesIndexCommand_withLsnAsVersion_andRecordsLag() {
        listener.onMessage(record(K4, ROW, "57798856", String.valueOf(NOW.minusSeconds(2).toEpochMilli())));

        assertThat(useCase.indexed).singleElement().isEqualTo(new IndexProductCommand(UUID.fromString(K4),
                "한정판 키캡", "재고 1개", 12_100, true, Instant.parse("2026-11-25T05:00:00.123456Z"), 57_798_856L));
        assertThat(meters.get("search_indexing_lag_seconds").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("search_indexed_total").tag("result", "applied").counter().count()).isEqualTo(1);
    }

    @Test
    void staleVersion_countsAsSkipped() {
        useCase.result = false;

        listener.onMessage(record(K4, ROW, "1", null));

        assertThat(meters.get("search_indexed_total").tag("result", "skipped").counter().count()).isEqualTo(1);
    }

    @Test
    void tombstone_removes_withVersionIfPresent() {
        listener.onMessage(record(K4, null, null, null));
        listener.onMessage(record(K5, null, "99", null));

        assertThat(useCase.removed).containsExactly(
                new RemoveProductCommand(UUID.fromString(K4), null), new RemoveProductCommand(UUID.fromString(K5), 99L));
    }

    @Test
    void contractViolations_areNotRetried() {
        assertThatThrownBy(() -> listener.onMessage(record(K4, ROW, null, null)))
                .isInstanceOf(NonRetryableMessageException.class).hasMessageContaining("__lsn");
        assertThatThrownBy(() -> listener.onMessage(record("x", ROW, "1", null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(K5, ROW, "1", null)))
                .isInstanceOf(NonRetryableMessageException.class).hasMessageContaining("key " + K5 + " != id " + K4);
        assertThatThrownBy(() -> listener.onMessage(record(K4, "{\"id\":\"" + K4 + "\",\"name\":\"a\"}", "1", null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(K4, ROW, "abc", null)))
                .isInstanceOf(NonRetryableMessageException.class);
        // 숫자 키(옛 bigint id)·표준 표기가 아닌 UUID·본문 id 가 UUID 가 아님
        assertThatThrownBy(() -> listener.onMessage(record("4", ROW, "1", null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record("1-1-1-1-1", ROW, "1", null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(K4, ROW.replace("\"" + K4 + "\"", "4"), "1", null)))
                .isInstanceOf(NonRetryableMessageException.class).hasMessageContaining("id");
        assertThat(useCase.indexed).isEmpty();
    }

    static final class RecordingUseCase implements IndexProductUseCase {
        final List<IndexProductCommand> indexed = new ArrayList<>();
        final List<RemoveProductCommand> removed = new ArrayList<>();
        boolean result = true;

        @Override
        public boolean index(IndexProductCommand command) {
            indexed.add(command);
            return result;
        }

        @Override
        public boolean remove(RemoveProductCommand command) {
            removed.add(command);
            return true;
        }
    }
}
