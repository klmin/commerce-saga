package com.klmin.commerce.searchservice;

import com.klmin.commerce.searchservice.product.application.port.in.ProductSearchResult;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsQuery;
import com.klmin.commerce.searchservice.product.application.port.in.SearchProductsUseCase;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * product.cdc.products → 리스너 → Elasticsearch → 검색 API 까지 (ADR 0010).
 * 같은 상품의 옛 변경이 늦게 와도 검색 결과가 되돌아가지 않고, tombstone 이면 빠진다
 */
@SpringBootTest(properties = "management.logging.export.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ProductCdcIndexingIntegrationTest {

    private static final String TOPIC = "product.cdc.products";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    SearchProductsUseCase search;

    private void send(long id, String value, long lsn) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, String.valueOf(id), value);
        record.headers().add(new RecordHeader("__lsn", String.valueOf(lsn).getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("__source_ts_ms",
                String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).join();
    }

    private static String row(long id, String name, long price, boolean onSale) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"description\":\"\",\"price\":" + price
                + ",\"on_sale\":" + onSale + ",\"updated_at\":\"2026-11-25T05:00:00Z\"}";
    }

    private List<ProductSearchResult.Item> find(String keyword) {
        return search.search(new SearchProductsQuery(keyword, null, null, false, null, 0, 20)).items();
    }

    @Test
    void indexesChanges_ignoresLateOldChange_andRemovesOnTombstone() {
        send(101, row(101, "통합 시험 키보드", 30_000, true), 1_000);
        send(101, row(101, "통합 시험 키보드", 31_000, true), 1_001);
        // 늦게 온 옛 변경
        send(101, row(101, "통합 시험 키보드", 29_000, true), 999);
        // 처리 순서 표시용 (같은 파티션이 아니어도 위 셋이 끝날 시간을 준다)
        send(102, row(102, "통합 시험 마커", 1_000, true), 1_000);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(find("통합 시험 마커")).hasSize(1);
            assertThat(find("통합 시험 키보드")).singleElement()
                    .satisfies(i -> assertThat(i.price()).isEqualTo(31_000));
        });

        send(101, null, 1_002);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(find("통합 시험 키보드")).isEmpty());
    }
}
