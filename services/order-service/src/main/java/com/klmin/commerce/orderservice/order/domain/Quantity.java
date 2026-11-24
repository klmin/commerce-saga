package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/** 주문 수량 (값 객체). 1 이상. 항목별 상한은 {@link OrderLine#MAX_QUANTITY} */
public record Quantity(int value) {

    public Quantity {
        if (value <= 0) {
            throw new DomainValidationException("quantity must be positive: " + value);
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }
}
