package com.klmin.commerce.productservice.product.domain;

import com.klmin.commerce.productservice.common.error.DomainValidationException;

/** 상품 id. DB 가 매긴다(1~5 는 시드, inventory 재고 시드와 같은 id) */
public record ProductId(long value) {

    public ProductId {
        if (value <= 0) {
            throw new DomainValidationException("productId must be positive: " + value);
        }
    }

    public static ProductId of(long value) {
        return new ProductId(value);
    }
}
