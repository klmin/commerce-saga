package com.klmin.commerce.inventoryservice.stock.domain;

import java.util.Objects;
import java.util.UUID;

/** 상품 id (값 객체, UUIDv7). 다른 UUID(주문 id 등)와 바꿔 넘기는 실수를 타입으로 막는다 */
public record ProductId(UUID value) implements Comparable<ProductId> {

    public ProductId {
        Objects.requireNonNull(value, "productId");
    }

    public static ProductId of(UUID value) {
        return new ProductId(value);
    }

    @Override
    public int compareTo(ProductId other) {
        return value.compareTo(other.value);
    }
}
