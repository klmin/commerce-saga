package com.klmin.commerce.authservice.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshFamilyTest {

    private static final String FAMILY = "0b0f7e6e-3f5c-4c8e-9a7e-2f6d8c1b4a11";
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration ABSOLUTE = Duration.ofDays(30);
    private static final Duration GRACE = Duration.ofSeconds(20);
    private static final Instant T0 = Instant.parse("2026-11-23T00:00:00Z");

    private final RefreshToken first = token('a');
    private final RefreshToken second = token('b');
    private final RefreshToken third = token('c');

    @Test
    void 현재_secret_이면_회전하고_직전_secret_은_유예_동안만_GRACE() {
        RefreshFamily family = RefreshFamily.start(first, 1L, "agent", T0, TTL, ABSOLUTE);
        assertThat(family.decide(first, T0.plusSeconds(1))).isEqualTo(RefreshDecision.ROTATE);

        RefreshFamily rotated = family.rotate(second, T0.plusSeconds(1), TTL, GRACE);

        assertThat(rotated.decide(second, T0.plusSeconds(2))).isEqualTo(RefreshDecision.ROTATE);
        assertThat(rotated.decide(first, T0.plusSeconds(20))).isEqualTo(RefreshDecision.GRACE);
        assertThat(rotated.decide(first, T0.plusSeconds(21))).isEqualTo(RefreshDecision.REUSE_DETECTED);
    }

    @Test
    void 두_번_전_secret_이나_모르는_secret_은_재사용_탐지() {
        RefreshFamily family = RefreshFamily.start(first, 1L, "agent", T0, TTL, ABSOLUTE)
                .rotate(second, T0.plusSeconds(1), TTL, GRACE)
                .rotate(third, T0.plusSeconds(2), TTL, GRACE);

        assertThat(family.decide(first, T0.plusSeconds(3))).isEqualTo(RefreshDecision.REUSE_DETECTED);
        assertThat(family.decide(second, T0.plusSeconds(3))).isEqualTo(RefreshDecision.GRACE);
        assertThat(family.decide(token('z'), T0.plusSeconds(3))).isEqualTo(RefreshDecision.REUSE_DETECTED);
    }

    @Test
    void 쓰면_수명이_연장되지만_절대_만료를_넘지_않는다() {
        RefreshFamily family = RefreshFamily.start(first, 1L, "agent", T0, TTL, ABSOLUTE);
        assertThat(family.expiresAt()).isEqualTo(T0.plus(TTL));

        Instant day20 = T0.plus(Duration.ofDays(20));
        RefreshFamily extended = family.rotate(second, T0.plus(Duration.ofDays(10)), TTL, GRACE)
                .rotate(third, day20, TTL, GRACE);

        assertThat(extended.expiresAt()).isEqualTo(T0.plus(ABSOLUTE));
        assertThat(extended.decide(third, T0.plus(ABSOLUTE).minusSeconds(1))).isEqualTo(RefreshDecision.ROTATE);
        assertThat(extended.decide(third, T0.plus(ABSOLUTE))).isEqualTo(RefreshDecision.EXPIRED);
    }

    @Test
    void 쓰지_않고_수명이_지나면_만료() {
        RefreshFamily family = RefreshFamily.start(first, 1L, "agent", T0, TTL, ABSOLUTE);

        assertThat(family.decide(first, T0.plus(TTL))).isEqualTo(RefreshDecision.EXPIRED);
        assertThat(family.remainingLifetime(T0.plus(TTL).plusSeconds(5))).isZero();
    }

    @Test
    void 다른_family_의_토큰으로는_회전할_수_없다() {
        RefreshFamily family = RefreshFamily.start(first, 1L, "agent", T0, TTL, ABSOLUTE);
        RefreshToken other = new RefreshToken("1b0f7e6e-3f5c-4c8e-9a7e-2f6d8c1b4a11", second.secret());

        assertThatThrownBy(() -> family.rotate(other, T0, TTL, GRACE)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RefreshToken token(char c) {
        return new RefreshToken(FAMILY, String.valueOf(c).repeat(43));
    }
}
