package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.UUID;

/**
 * 본인 주문 목록 (최신순, 커서 페이징).
 *
 * @param before 이 주문보다 오래된 것만 (null 이면 처음부터). 주문 id 가 UUIDv7 이라 id 순서 = 생성 순서
 * @param size   1 ~ {@link #MAX_SIZE}
 */
public record ListMyOrdersQuery(long userId, UUID before, int size) {

    public static final int MAX_SIZE = 50;

    public ListMyOrdersQuery {
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainValidationException("size must be between 1 and " + MAX_SIZE);
        }
    }
}
