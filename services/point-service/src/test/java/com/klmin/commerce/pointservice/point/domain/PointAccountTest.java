package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.InvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointAccountTest {

    private static final Instant T0 = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void use_and_refund() {
        PointAccount account = PointAccount.restore(1L, 1_000L, T0);

        account.use(300L, T0);
        assertThat(account.balance()).isEqualTo(700L);

        account.refund(300L, T0);
        assertThat(account.balance()).isEqualTo(1_000L);
    }

    @Test
    void canUse_upToBalance_andPositiveOnly() {
        PointAccount account = PointAccount.restore(1L, 100L, T0);

        assertThat(account.canUse(100L)).isTrue();
        assertThat(account.canUse(101L)).isFalse();
        assertThat(account.canUse(0L)).isFalse();
        assertThatThrownBy(() -> account.use(101L, T0)).isInstanceOf(IllegalStateException.class);
        assertThat(account.balance()).isEqualTo(100L);
    }

    @Test
    void refund_nonPositive_isInvariantViolation() {
        PointAccount account = PointAccount.restore(1L, 100L, T0);

        assertThatThrownBy(() -> account.refund(0L, T0)).isInstanceOf(InvariantViolationException.class);
    }

    @Test
    void usage_refundOnlyFromUsed() {
        PointUsage used = PointUsage.used(UUID.randomUUID(), 1L, 100L, T0);
        assertThat(used.refund(T0)).isTrue();
        assertThat(used.status()).isEqualTo(PointUsageStatus.REFUNDED);
        assertThat(used.refund(T0)).isFalse();

        PointUsage rejected = PointUsage.rejected(UUID.randomUUID(), 1L, 100L, "insufficient", T0);
        assertThat(rejected.refund(T0)).isFalse();
        assertThat(rejected.status()).isEqualTo(PointUsageStatus.REJECTED);
    }
}
