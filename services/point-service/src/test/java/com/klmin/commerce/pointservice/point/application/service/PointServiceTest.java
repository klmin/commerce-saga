package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.point.application.PointAccountNotFoundException;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.out.PointAccountPort;
import com.klmin.commerce.pointservice.point.application.port.out.PointUsagePort;
import com.klmin.commerce.pointservice.point.application.port.out.SendPointReplyPort;
import com.klmin.commerce.pointservice.point.domain.PointAccount;
import com.klmin.commerce.pointservice.point.domain.PointUsage;
import com.klmin.commerce.pointservice.point.domain.PointUsageStatus;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
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

    /** 테스트 사용자 id: 숫자 n 을 끝자리로 갖는 고정 UUID (시드 user1~4 와 같은 표기) */
    static UUID uid(long n) {
        return UUID.fromString(String.format("01900000-0000-7000-8001-%012x", n));
    }

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final InMemoryUsages usages = new InMemoryUsages();
    private final RecordingReplies replies = new RecordingReplies();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final PointService service =
            new PointService(accounts, usages, replies, processed, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void use_deductsAndRepliesUsed() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        assertThat(accounts.balance(1L)).isEqualTo(700L);
        assertThat(usages.store.get(orderId).status()).isEqualTo(PointUsageStatus.USED);
        assertThat(replies.log).containsExactly("used:" + orderId);
    }

    @Test
    void use_insufficient_deductsNothing_andRepliesFailed() {
        accounts.put(1L, 100L);
        UUID orderId = UUID.randomUUID();

        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).containsExactly("failed:" + orderId + ":insufficient points: userId=" + uid(1L));
    }

    @Test
    void use_unknownAccount_orInvalidAmount_repliesFailed() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        accounts.put(1L, 100L);

        service.use(UUID.randomUUID(), new UsePointCommand(a, uid(99L), 10L));
        service.use(UUID.randomUUID(), new UsePointCommand(b, uid(1L), 0L));
        service.use(UUID.randomUUID(), new UsePointCommand(c, null, 10L));

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).containsExactly(
                "failed:" + a + ":point account not found: userId=" + uid(99L),
                "failed:" + b + ":invalid command: amount must be positive",
                "failed:" + c + ":invalid command: userId missing");
    }

    @Test
    void duplicateUse_deductsOnce_andRepliesSameResult() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));
        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        assertThat(accounts.balance(1L)).isEqualTo(700L);
        assertThat(replies.log).containsExactly("used:" + orderId, "used:" + orderId);
    }

    @Test
    void redeliveredMessage_isSkipped_withoutChangingBalanceOrReplying() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();
        UUID useId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();

        service.use(useId, new UsePointCommand(orderId, uid(1L), 300L));
        service.use(useId, new UsePointCommand(orderId, uid(1L), 300L));
        service.refund(refundId, orderId);
        service.refund(refundId, orderId);
        service.use(useId, new UsePointCommand(orderId, uid(1L), 300L));   // 보상 뒤 늦게 다시 온 원래 메시지

        assertThat(accounts.balance(1L)).isEqualTo(1_000L);
        assertThat(replies.log).containsExactly("used:" + orderId, "refunded:" + orderId);
        assertThat(processed.ids).containsExactlyInAnyOrder(useId, refundId);
    }

    @Test
    void refund_returnsPoints_once_andRepliesEveryTime() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();
        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        service.refund(UUID.randomUUID(), orderId);
        service.refund(UUID.randomUUID(), orderId);

        assertThat(accounts.balance(1L)).isEqualTo(1_000L);
        assertThat(usages.store.get(orderId).status()).isEqualTo(PointUsageStatus.REFUNDED);
        assertThat(replies.log).containsExactly("used:" + orderId, "refunded:" + orderId, "refunded:" + orderId);
    }

    @Test
    void refund_ofRejectedUsage_changesNothing() {
        accounts.put(1L, 100L);
        UUID orderId = UUID.randomUUID();
        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        service.refund(UUID.randomUUID(), orderId);

        assertThat(accounts.balance(1L)).isEqualTo(100L);
        assertThat(replies.log).last().isEqualTo("refunded:" + orderId);
    }

    @Test
    void refund_withoutUsage_blocksLateUse() {
        accounts.put(1L, 1_000L);
        UUID orderId = UUID.randomUUID();

        service.refund(UUID.randomUUID(), orderId);
        service.use(UUID.randomUUID(), new UsePointCommand(orderId, uid(1L), 300L));

        assertThat(accounts.balance(1L)).isEqualTo(1_000L);
        assertThat(replies.log).containsExactly("refunded:" + orderId,
                "failed:" + orderId + ":point usage already refunded");
    }

    @Test
    void getBalance() {
        accounts.put(1L, 500L);

        assertThat(service.getBalance(uid(1L)).balance()).isEqualTo(Points.of(500L));
        assertThatThrownBy(() -> service.getBalance(uid(2L))).isInstanceOf(PointAccountNotFoundException.class);
    }

    static class InMemoryAccounts implements PointAccountPort {

        final Map<UserId, PointAccount> store = new HashMap<>();

        void put(long userId, long balance) {
            store.put(UserId.of(uid(userId)), PointAccount.restore(UserId.of(uid(userId)), Points.of(balance), NOW));
        }

        long balance(long userId) {
            return store.get(UserId.of(uid(userId))).balance().value();
        }

        @Override
        public Optional<PointAccount> findById(UserId userId) {
            return Optional.ofNullable(store.get(userId)).map(InMemoryAccounts::copy);
        }

        @Override
        public Optional<PointAccount> findByIdForUpdate(UserId userId) {
            return findById(userId);
        }

        @Override
        public void update(PointAccount account) {
            store.put(account.userId(), copy(account));
        }

        @Override
        public boolean insertIfAbsent(PointAccount account) {
            return store.putIfAbsent(account.userId(), copy(account)) == null;
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
