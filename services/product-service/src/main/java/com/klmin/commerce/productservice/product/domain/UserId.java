package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

/** 상품을 바꾼 관리자 = access token 의 sub */
public record UserId(long value) {

    public UserId {
        if (value <= 0) {
            throw new DomainValidationException("userId must be positive: " + value);
        }
    }

    public static UserId of(long value) {
        return new UserId(value);
    }
}
