package com.klmin.commerce.pointservice.point.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 포인트 지급 기록 (주문과 무관하게 잔액이 늘어난 일). 한 번 쓰면 바뀌지 않는다.
 * 잔액은 {@link PointAccount} 가 가지고, 이 기록은 내역을 보여 주려고 남긴다.
 */
public record PointGrant(UUID id, UserId userId, Points amount, PointGrantType type, Instant createdAt) {

    public PointGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("grant amount must be positive: " + amount.value());
        }
    }

    public static PointGrant signupBonus(UUID id, UserId userId, Points amount, Instant now) {
        return new PointGrant(id, userId, amount, PointGrantType.SIGNUP_BONUS, now);
    }
}
