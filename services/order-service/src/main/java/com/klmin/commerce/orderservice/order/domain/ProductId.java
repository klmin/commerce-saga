package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/** 상품 id. 사용자 id 와 같은 long 이라 바꿔 넘기는 실수를 타입으로 막는다 */
public record ProductId(long value) {

    public ProductId {
        if (value <= 0) {
            throw new DomainValidationException("productId must be positive: " + value);
        }
    }

    public static ProductId of(long value) {
        return new ProductId(value);
    }
}
