package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/**
 * 주문에 쓰는 포인트 (값 객체). 0 이상. 잔액·차감 규칙은 point-service 의 일이고, 주문은 "얼마를 쓸지"만 가진다.
 * 1 포인트 = 1 원이며 그 환산은 {@link #toMoney()} 한 곳에서만 한다.
 */
public record Points(long value) {

    public static final Points ZERO = new Points(0L);

    public Points {
        if (value < 0) {
            throw new DomainValidationException("points must not be negative: " + value);
        }
    }

    public static Points of(long value) {
        return new Points(value);
    }

    public boolean isPositive() {
        return value > 0;
    }

    /** 결제 금액에서 빼는 원화 금액 (1P = 1원) */
    public Money toMoney() {
        return new Money(value);
    }
}
