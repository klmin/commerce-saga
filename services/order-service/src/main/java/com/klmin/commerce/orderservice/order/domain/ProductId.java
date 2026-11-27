package com.klmin.commerce.orderservice.order.domain;

import com.klmin.commerce.orderservice.common.error.DomainValidationException;

import java.util.UUID;

/** 상품 id (product-service 가 만든 UUIDv7, ADR 0018). 다른 컨텍스트의 식별자라 값만 가진다 */
public record ProductId(UUID value) {

    public ProductId {
        if (value == null) {
            throw new DomainValidationException("productId must not be null");
        }
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }
}
