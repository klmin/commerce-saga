package com.klmin.commerce.pointservice.point.application.service;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountCommand;
import com.klmin.commerce.pointservice.point.domain.PointGrant;
import com.klmin.commerce.pointservice.point.domain.PointGrantType;
import com.klmin.commerce.pointservice.point.domain.Points;
import com.klmin.commerce.pointservice.point.domain.UserId;
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
    private final InMemoryPointGrants grants = new InMemoryPointGrants();
    private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    private final PointAccountService service = new PointAccountService(accounts, grants, processed,
            new PointAccountProperties(1_000L), Clock.fixed(NOW, ZoneOffset.UTC));

    private static OpenPointAccountCommand registered(long userId) {
        return new OpenPointAccountCommand(PointServiceTest.uid(userId), NOW);
    }

    @Test
    void newUser_getsAccountWithSignupBonus_andGrantRecord() {
        service.open(UUID.randomUUID(), registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(1_000L);
        assertThat(grants.grants).singleElement().satisfies(g -> {
            assertThat(g.userId()).isEqualTo(UserId.of(PointServiceTest.uid(1000L)));
            assertThat(g.amount()).isEqualTo(Points.of(1_000L));
            assertThat(g.type()).isEqualTo(PointGrantType.SIGNUP_BONUS);
            assertThat(g.createdAt()).isEqualTo(NOW);
            assertThat(g.id().version()).isEqualTo(7);
        });
    }

    @Test
    void redeliveredMessage_isSkipped() {
        UUID messageId = UUID.randomUUID();
        service.open(messageId, registered(1000L));
        accounts.put(1000L, 300L); // 그사이 포인트를 썼다

        service.open(messageId, registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(300L);
        assertThat(processed.ids).containsExactly(messageId);
        assertThat(grants.grants).hasSize(1);
    }

    @Test
    void sameEventAsAnotherMessage_doesNotResetOrAddBonus() {
        service.open(UUID.randomUUID(), registered(1000L));
        accounts.put(1000L, 300L);

        service.open(UUID.randomUUID(), registered(1000L));

        assertThat(accounts.balance(1000L)).isEqualTo(300L);
        assertThat(grants.grants).hasSize(1);
    }

    @Test
    void existingSeedAccount_isLeftAsIs_withoutGrantRecord() {
        accounts.put(1L, 10_000L);

        service.open(UUID.randomUUID(), registered(1L));

        assertThat(accounts.balance(1L)).isEqualTo(10_000L);
        assertThat(grants.grants).isEmpty();
    }

    @Test
    void bonusIsConfigurable_andMustNotBeNegative() {
        PointAccountService noBonus = new PointAccountService(accounts, grants, processed,
                new PointAccountProperties(0L), Clock.fixed(NOW, ZoneOffset.UTC));
        noBonus.open(UUID.randomUUID(), registered(2000L));

        assertThat(accounts.balance(2000L)).isZero();
        assertThat(grants.grants).isEmpty(); // 0P 지급은 내역에 남기지 않는다
        assertThat(new PointAccountProperties(null).signupBonus()).isEqualTo(1_000L);
        assertThatThrownBy(() -> new PointAccountProperties(-1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.open(UUID.randomUUID(), new OpenPointAccountCommand(null, NOW)))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void grantRecord_mustBePositive() {
        assertThatThrownBy(() -> PointGrant.signupBonus(UUID.randomUUID(), UserId.of(PointServiceTest.uid(1L)), Points.ZERO, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
