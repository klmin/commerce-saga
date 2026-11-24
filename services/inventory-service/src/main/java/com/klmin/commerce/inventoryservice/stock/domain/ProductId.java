package com.klmin.commerce.inventoryservice.stock.domain;

/** 상품 id (값 객체). 양수. 수량과 같은 숫자라 바꿔 넘기는 실수를 타입으로 막는다 */
public record ProductId(long value) implements Comparable<ProductId> {

    public ProductId {
        if (value <= 0) {
            throw new IllegalArgumentException("productId must be positive: " + value);
        }
    }

    public static ProductId of(long value) {
        return new ProductId(value);
    }

    @Override
    public int compareTo(ProductId other) {
        return Long.compare(value, other.value);
    }
}
