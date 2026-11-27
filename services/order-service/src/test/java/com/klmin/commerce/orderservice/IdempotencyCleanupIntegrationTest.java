package com.klmin.commerce.orderservice;

import com.klmin.commerce.orderservice.order.application.port.in.CleanupIdempotencyRecordsUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.klmin.commerce.orderservice.TestProductIds.pid;
import static com.klmin.commerce.orderservice.TestUserIds.uid;
import static org.assertj.core.api.Assertions.assertThat;

/** 보존 기간(30일·키 7일)이 지난 멱등 기록만 지운다 (PROGRESS 4-4절 ②) */
@SpringBootTest(properties = {"management.logging.export.enabled=false", "idempotency.cleanup.enabled=false",
        "idempotency.cleanup.batch-size=2"})
@Import({TestcontainersConfiguration.class, StubProductCatalogConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyCleanupIntegrationTest {

    @Autowired
    CleanupIdempotencyRecordsUseCase cleanup;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlaceOrderUseCase placeOrder;

    private UUID processedAt(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into processed_message (message_id, processed_at) values (?, ?)", id, Timestamp.from(at));
        return id;
    }

    private boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from processed_message where message_id = ?)", Boolean.class, id));
    }

    @Test
    void deletesOnlyExpiredRecords_inBatches() {
        Instant now = Instant.now();
        UUID old1 = processedAt(now.minus(Duration.ofDays(40)));
        UUID old2 = processedAt(now.minus(Duration.ofDays(35)));
        UUID old3 = processedAt(now.minus(Duration.ofDays(31)));
        UUID recent = processedAt(now.minus(Duration.ofDays(29)));
        String oldKey = "cleanup-old-" + UUID.randomUUID();
        String newKey = "cleanup-new-" + UUID.randomUUID();
        UUID oldOrder = placeOrder.placeOrder(new PlaceOrderCommand(uid(80_001),
                List.of(new PlaceOrderCommand.Line(pid(10), 1, StubProductCatalogConfiguration.PRICE)), 0L, oldKey)).order().id();
        placeOrder.placeOrder(new PlaceOrderCommand(uid(80_001),
                List.of(new PlaceOrderCommand.Line(pid(10), 1, StubProductCatalogConfiguration.PRICE)), 0L, newKey));
        jdbc.update("update order_idempotency_keys set created_at = ? where idempotency_key = ?",
                Timestamp.from(now.minus(Duration.ofDays(8))), oldKey);

        cleanup.cleanup();

        assertThat(exists(old1)).isFalse();
        assertThat(exists(old2)).isFalse();
        assertThat(exists(old3)).isFalse();
        assertThat(exists(recent)).isTrue();
        assertThat(jdbc.queryForList("select idempotency_key from order_idempotency_keys where user_id = ?",
                String.class, uid(80_001))).containsExactly(newKey);
        // 키만 지우고 주문은 남는다
        assertThat(jdbc.queryForObject("select count(*) from orders where id = ?", Integer.class, oldOrder)).isEqualTo(1);
    }
}
