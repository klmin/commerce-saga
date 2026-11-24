package com.klmin.commerce.pointservice.point.domain;

import com.klmin.commerce.pointservice.common.error.DomainValidationException;

/** 포인트 계정 주인 (auth-service 가 발급한 id). 다른 컨텍스트의 식별자라 값만 가진다 */
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
