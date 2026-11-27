package com.klmin.commerce.paymentservice.payment.application.port.in;

import com.klmin.commerce.paymentservice.common.error.DomainValidationException;

import java.util.UUID;

/**
 * 본인 결제 목록 (최신순, 커서 페이징).
 *
 * @param before 이 결제보다 오래된 것만 (null 이면 처음부터). 결제 id 가 UUIDv7 이라 id 순서 = 생성 순서
 * @param size   1 ~ {@link #MAX_SIZE}
 */
public record ListMyPaymentsQuery(UUID userId, UUID before, int size) {

    public static final int MAX_SIZE = 50;

    public ListMyPaymentsQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainValidationException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
