package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

/** 주문한 사용자 (auth-service 가 발급한 id, access token 의 sub). 다른 컨텍스트의 식별자라 값만 가진다 */
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
