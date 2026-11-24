package com.klmin.commerce.productservice.product.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 가격 변경 이력 한 건 (audit). 가격을 바꾼 트랜잭션에서 함께 남기고 고치지 않는다.
 * JPA Auditing·수정자 컬럼 대신 도메인 이력으로 둔다 (ADR 0015·0016)
 */
public record PriceChange(ProductId productId, Money oldPrice, Money newPrice, UserId changedBy, Instant changedAt) {

    public PriceChange {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(oldPrice, "oldPrice");
        Objects.requireNonNull(newPrice, "newPrice");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
