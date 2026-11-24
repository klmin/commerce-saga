package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/**
 * 원화 금액 (값 객체). 0 이상이고 계산 결과도 음수가 되지 않는다. 넘치면 ArithmeticException.
 * 포인트({@link Points})와는 다른 타입이라 섞어 더할 수 없다. 포인트를 금액으로 쓸 때는 {@link Points#toMoney()}.
 */
public record Money(long value) implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    public Money {
        if (value < 0) {
            throw new DomainValidationException("money must not be negative: " + value);
        }
    }

    public static Money of(long value) {
        return new Money(value);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(value, other.value));
    }

    /** 결과가 음수면 DomainValidationException */
    public Money minus(Money other) {
        return new Money(value - other.value);
    }

    public Money times(Quantity quantity) {
        return new Money(Math.multiplyExact(value, (long) quantity.value()));
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isGreaterThan(Money other) {
        return value > other.value;
    }

    @Override
    public int compareTo(Money other) {
        return Long.compare(value, other.value);
    }
}
