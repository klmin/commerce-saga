package com.klmin.commerce.productservice.product.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 상품 관리 이력 한 건 (audit, ADR 0010 결정 12). 누가(JWT sub)·언제·무엇을 바꿨는지와 바꾼 전·후 값.
 * 상품을 바꾼 트랜잭션에서 함께 남기고 고치지 않는다. 등록은 이전 값이 없다(before = null).
 * JPA Auditing·수정자 컬럼 대신 도메인 이력으로 둔다 (ADR 0015, 가격 이력 {@link PriceChange} 와 같은 방식)
 */
public record ProductChange(ProductId productId, ProductAction action, ProductSnapshot before,
                            ProductSnapshot after, UserId changedBy, Instant changedAt) {

    public ProductChange {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
        if ((action == ProductAction.REGISTERED) != (before == null)) {
            throw new IllegalArgumentException("only REGISTERED has no before snapshot");
        }
    }

    /** 새 상품 (저장해 id 를 받은 뒤) */
    public static ProductChange registered(Product saved, UserId changedBy, Instant now) {
        return new ProductChange(saved.id(), ProductAction.REGISTERED, null, saved.snapshot(), changedBy, now);
    }

    /** 값이 실제로 바뀐 때만 이력. 같은 값으로 "바꾸기"는 남기지 않는다 */
    public static Optional<ProductChange> between(Product product, ProductAction action, ProductSnapshot before,
                                                  UserId changedBy, Instant now) {
        ProductSnapshot after = product.snapshot();
        if (after.equals(before)) {
            return Optional.empty();
        }
        return Optional.of(new ProductChange(product.id(), action, before, after, changedBy, now));
    }
}
