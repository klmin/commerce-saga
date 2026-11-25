package com.klmin.commerce.inventoryservice;

import com.klmin.commerce.inventoryservice.stock.application.port.in.CleanupIdempotencyRecordsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 보존 기간(30일)이 지난 멱등 기록만 지운다 (PROGRESS 4-4절 ②) */
@SpringBootTest(properties = {"management.logging.export.enabled=false", "idempotency.cleanup.enabled=false",
        "idempotency.cleanup.batch-size=2"})
@Import({TestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyCleanupIntegrationTest {

    @Autowired
    CleanupIdempotencyRecordsUseCase cleanup;

    @Autowired
    JdbcTemplate jdbc;

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

        cleanup.cleanup();

        assertThat(exists(old1)).isFalse();
        assertThat(exists(old2)).isFalse();
        assertThat(exists(old3)).isFalse();
        assertThat(exists(recent)).isTrue();
    }
}
