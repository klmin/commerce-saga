package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

import java.util.UUID;

/** 상품을 바꾼 관리자 = access token 의 sub (auth-service 가 만든 UUIDv7, ADR 0018) */
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
