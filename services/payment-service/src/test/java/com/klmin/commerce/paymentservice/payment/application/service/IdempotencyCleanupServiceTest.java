package com.klmin.commerce.paymentservice.payment.application.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");

    private final List<Instant> cutoffs = new ArrayList<>();
    private int remaining;

    private IdempotencyCleanupService service(int batchSize, int maxBatches) {
        return new IdempotencyCleanupService((cutoff, limit) -> {
            cutoffs.add(cutoff);
            int deleted = Math.min(limit, remaining);
            remaining -= deleted;
            return deleted;
        }, new IdempotencyCleanupProperties(Duration.ofDays(30), batchSize, maxBatches),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void deletesInBatchesUntilLessThanBatchSize_withRetentionCutoff() {
        remaining = 2_500;

        int deleted = service(1_000, 100).cleanup();

        assertThat(deleted).isEqualTo(2_500);
        assertThat(cutoffs).hasSize(3).allMatch(c -> c.equals(NOW.minus(Duration.ofDays(30))));
    }

    @Test
    void stopsAtMaxBatchesPerRun_restIsLeftForNextRun() {
        remaining = 10_000;

        assertThat(service(1_000, 3).cleanup()).isEqualTo(3_000);
        assertThat(remaining).isEqualTo(7_000);
    }
}
