package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

import java.util.UUID;

/** 상품 id (UUIDv7). 등록할 때 앱이 만든다(시드 5개는 inventory 재고 시드와 같은 고정 UUID, ADR 0018) */
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
