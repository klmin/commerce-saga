package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.InvariantViolationException;

import java.time.Instant;
import java.util.Objects;

/** 사용자별 포인트 잔액 애그리거트 */
public final class PointAccount {

    private final UserId userId;
    private Points balance;
    private Instant updatedAt;

    private PointAccount(UserId userId, Points balance, Instant updatedAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.balance = Objects.requireNonNull(balance, "balance");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 가입한 사용자의 새 계정. 시작 잔액은 가입 축하 포인트 (0 이상) */
    public static PointAccount open(UserId userId, Points initialBalance, Instant now) {
        return new PointAccount(userId, initialBalance, now);
    }

    public static PointAccount restore(UserId userId, Points balance, Instant updatedAt) {
        return new PointAccount(userId, balance, updatedAt);
    }

    public boolean canUse(Points amount) {
        return amount.isPositive() && !amount.isGreaterThan(balance);
    }

    /** 먼저 {@link #canUse} 로 확인해야 한다 */
    public void use(Points amount, Instant now) {
        if (!canUse(amount)) {
            throw new IllegalStateException(
                    "cannot use " + amount.value() + " points (balance=" + balance.value() + ")");
        }
        balance = balance.minus(amount);
        updatedAt = now;
    }

    /** 보상: 사용 기록의 금액만큼 돌려준다 */
    public void refund(Points amount, Instant now) {
        if (!amount.isPositive()) {
            throw new InvariantViolationException("refund amount must be positive: " + amount.value());
        }
        balance = balance.plus(amount);
        updatedAt = now;
    }

    public UserId userId() {
        return userId;
    }

    public Points balance() {
        return balance;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
