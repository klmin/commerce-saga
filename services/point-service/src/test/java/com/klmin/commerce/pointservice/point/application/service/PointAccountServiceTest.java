package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountCommand;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

    private final PointServiceTest.InMemoryAccounts accounts = new PointServiceTest.InMemoryAccounts();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final PointAccountService service = new PointAccountService(accounts, processed,
            new PointAccountProperties(1_000L), Clock.fixed(NOW, ZoneOffset.UTC));

    private static OpenPointAccountCommand registered(long userId) {
        return new OpenPointAccountCommand(userId, NOW);
    }

    @Test
    void newUser_getsAccountWithSignupBonus() {
        service.open(UUID.randomUUID(), registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(1_000L);
    }

    @Test
    void redeliveredMessage_isSkipped() {
        UUID messageId = UUID.randomUUID();
        service.open(messageId, registered(1000L));
        accounts.put(1000L, 300L); // 그사이 포인트를 썼다

        service.open(messageId, registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(300L);
        assertThat(processed.ids).containsExactly(messageId);
    }

    @Test
    void sameEventAsAnotherMessage_doesNotResetOrAddBonus() {
        service.open(UUID.randomUUID(), registered(1000L));
        accounts.put(1000L, 300L);

        service.open(UUID.randomUUID(), registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(300L);
    }

    @Test
    void existingSeedAccount_isLeftAsIs() {
        accounts.put(1L, 10_000L);

        service.open(UUID.randomUUID(), registered(1L));

        assertThat(accounts.balance(1L)).isEqualTo(10_000L);
    }

    @Test
    void bonusIsConfigurable_andMustNotBeNegative() {
        PointAccountService noBonus = new PointAccountService(accounts, processed, new PointAccountProperties(0L),
                Clock.fixed(NOW, ZoneOffset.UTC));
        noBonus.open(UUID.randomUUID(), registered(2000L));

        assertThat(accounts.balance(2000L)).isZero();
        assertThat(new PointAccountProperties(null).signupBonus()).isEqualTo(1_000L);
        assertThatThrownBy(() -> new PointAccountProperties(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.open(UUID.randomUUID(), registered(0L)))
                .isInstanceOf(DomainValidationException.class);
    }
}
