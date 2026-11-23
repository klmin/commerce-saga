package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;
import com.klmin.commerce.pointservice.common.error.InvariantViolationException;

import java.time.Instant;
import java.util.Objects;

/** 사용자별 포인트 잔액 애그리거트 */
public final class PointAccount {

    private final long userId;
    private long balance;
    private Instant updatedAt;

    private PointAccount(long userId, long balance, Instant updatedAt) {
        if (balance < 0) {
            throw new IllegalArgumentException("balance must not be negative");
        }
        this.userId = userId;
        this.balance = balance;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** 가입한 사용자의 새 계정. 시작 잔액은 가입 축하 포인트 (0 이상) */
    public static PointAccount open(long userId, long initialBalance, Instant now) {
        if (userId <= 0) {
            throw new DomainValidationException("userId must be positive: " + userId);
        }
        if (initialBalance < 0) {
            throw new DomainValidationException("initial balance must not be negative: " + initialBalance);
        }
        return new PointAccount(userId, initialBalance, now);
    }

    public static PointAccount restore(long userId, long balance, Instant updatedAt) {
        return new PointAccount(userId, balance, updatedAt);
    }

    public boolean canUse(long amount) {
        return amount > 0 && amount <= balance;
    }

    /** 먼저 {@link #canUse} 로 확인해야 한다 */
    public void use(long amount, Instant now) {
        if (!canUse(amount)) {
            throw new IllegalStateException("cannot use " + amount + " points (balance=" + balance + ")");
        }
        balance -= amount;
        updatedAt = now;
    }

    /** 보상: 사용 기록의 금액만큼 돌려준다 */
    public void refund(long amount, Instant now) {
        if (amount <= 0) {
            throw new InvariantViolationException("refund amount must be positive: " + amount);
        }
        balance = Math.addExact(balance, amount);
        updatedAt = now;
    }

    public long userId() {
        return userId;
    }

    public long balance() {
        return balance;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
