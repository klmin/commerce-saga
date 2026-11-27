package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.UUID;

/** 주문한 사용자 (auth-service 가 만든 UUIDv7, access token 의 sub, ADR 0018). 다른 컨텍스트의 식별자라 값만 가진다 */
public record UserId(UUID value) {

    public UserId {
        if (value == null) {
            throw new DomainValidationException("userId must not be null");
        }
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }
}
