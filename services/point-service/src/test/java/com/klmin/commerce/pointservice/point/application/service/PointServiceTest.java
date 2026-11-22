package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.PointAccountNotFoundException;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.application.port.out.PointUsagePort;
import com.klmin.commerce.pointservice.point.application.port.out.SendPointReplyPort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.PointUsage;
import com.klmin.commerce.pointservice.point.domain.PointUsageStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final InMemoryUsages usages = new InMemoryUsages();
    private final RecordingReplies replies = new RecordingReplies();
    private final PointService service =
            new PointService(accounts, usages, replies, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void use_deductsAndRepliesUsed() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.use(new UsePointCommand(orderId, 1L, 300L));

        assertThat(accounts.balance(1L)).isEqualTo(700L);
        assertThat(usages.store.get(orderId).status()).isEqualTo(PointUsageStatus.USED);
        assertThat(replies.log).containsExactly("used:" + orderId);
    }

    @Test
    void use_insufficient_deductsNothing_andRepliesFailed() {
        accounts.put(1L, 100L);
        UUID orderId = UUID.randomUUID();

        service.use(new UsePointCommand(orderId, 1L, 300L));

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":insufficient points: userId=1");
    }

    @Test
    void use_unknownAccount_orInvalidAmount_repliesFailed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        accounts.put(1L, 100L);

        service.use(new UsePointCommand(a, 99L, 10L));
        service.use(new UsePointCommand(b, 1L, 0L));

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).containsExactly(
                "failed:" + a + ":point account not found: userId=99",
                "failed:" + b + ":invalid command: amount must be positive");
    }

    @Test
    void duplicateUse_deductsOnce_andRepliesSameResult() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.use(new UsePointCommand(orderId, 1L, 300L));
        service.use(new UsePointCommand(orderId, 1L, 300L));

        assertThat(accounts.balance(1L)).isEqualTo(700L);
        assertThat(replies.log).containsExactly("used:" + orderId, "used:" + orderId);
    }

    @Test
    void refund_returnsPoints_once_andRepliesEveryTime() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();
        service.use(new UsePointCommand(orderId, 1L, 300L));

        service.refund(orderId);
        service.refund(orderId);

        assertThat(accounts.balance(1L)).isEqualTo(1_000L);
        assertThat(usages.store.get(orderId).status()).isEqualTo(PointUsageStatus.REFUNDED);
        assertThat(replies.log).containsExactly("used:" + orderId, "refunded:" + orderId, "refunded:" + orderId);
    }

    @Test
    void refund_ofRejectedUsage_changesNothing() {
        accounts.put(1L, 100L);
        UUID orderId = UUID.randomUUID();
        service.use(new UsePointCommand(orderId, 1L, 300L));

        service.refund(orderId);

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).last().isEqualTo("refunded:" + orderId);
    }

    @Test
    void refund_withoutUsage_blocksLateUse() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.refund(orderId);
        service.use(new UsePointCommand(orderId, 1L, 300L));

        assertThat(accounts.balance(1L)).isEqualTo(1_000L);
        assertThat(replies.log).containsExactly("refunded:" + orderId,
                "failed:" + orderId + ":point usage already refunded");
    }

    @Test
    void getBalance() {
        accounts.put(1L, 500L);

        assertThat(service.getBalance(1L).balance()).isEqualTo(500L);
        assertThatThrownBy(() -> service.getBalance(2L)).isInstanceOf(PointAccountNotFoundException.class);
    }

    static class InMemoryAccounts implements PointAccountPort {

        final Map<Long, PointAccount> store = new HashMap<>();

        void put(long userId, long balance) {
            store.put(userId, PointAccount.restore(userId, balance, NOW));
        }

        long balance(long userId) {
            return store.get(userId).balance();
        }

        @Override
        public Optional<PointAccount> findById(long userId) {
            return Optional.ofNullable(store.get(userId)).map(InMemoryAccounts::copy);
        }

        @Override
        public Optional<PointAccount> findByIdForUpdate(long userId) {
            return findById(userId);
        }

        @Override
        public void update(PointAccount account) {
            store.put(account.userId(), copy(account));
        }

        private static PointAccount copy(PointAccount a) {
            return PointAccount.restore(a.userId(), a.balance(), a.updatedAt());
        }
    }

    static class InMemoryUsages implements PointUsagePort {

        final Map<UUID, PointUsage> store = new HashMap<>();

        @Override
        public Optional<PointUsage> findByOrderId(UUID orderId) {
            return Optional.ofNullable(store.get(orderId)).map(InMemoryUsages::copy);
        }

        @Override
        public void insert(PointUsage usage) {
            if (store.putIfAbsent(usage.orderId(), copy(usage)) != null) {
                throw new IllegalStateException("duplicate usage");
            }
        }

        @Override
        public void update(PointUsage usage) {
            store.put(usage.orderId(), copy(usage));
        }

        private static PointUsage copy(PointUsage u) {
            return PointUsage.restore(u.orderId(), u.userId(), u.amount(), u.status(), u.reason(), u.createdAt(),
                    u.updatedAt());
        }
    }

    static class RecordingReplies implements SendPointReplyPort {

        final List<String> log = new ArrayList<>();

        @Override
        public void pointUsed(UUID orderId) {
            log.add("used:" + orderId);
        }

        @Override
        public void pointUseFailed(UUID orderId, String reason) {
            log.add("failed:" + orderId + ":" + reason);
        }

        @Override
        public void pointRefunded(UUID orderId) {
            log.add("refunded:" + orderId);
        }
    }
}
