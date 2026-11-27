package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;

import java.util.UUID;

/** 포인트 계정 주인 (auth-service 가 발급한 UUIDv7 id). 다른 컨텍스트의 식별자라 값만 가진다 */
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
