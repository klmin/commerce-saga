package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

/**
 * 상품 가격(원). 0 이상, 상한은 주문 금액 계산(가격 × 수량 합)이 long 범위를 넘지 않게 1억 원.
 * 값 객체는 서비스마다 따로 둔다 (order 의 Money 와 공유하지 않는다, ADR 0015)
 */
public record Money(long value) {

    public static final long MAX = 100_000_000L;

    public Money {
        if (value < 0 || value > MAX) {
            throw new DomainValidationException("price must be between 0 and " + MAX + ": " + value);
        }
    }

    public static Money of(long value) {
        return new Money(value);
    }

    public boolean isPositive() {
        return value > 0;
    }
}
