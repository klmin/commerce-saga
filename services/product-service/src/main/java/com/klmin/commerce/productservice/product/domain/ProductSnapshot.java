package com.klmin.commerce.productservice.product.domain;

import java.util.Objects;

/** 관리 이력에 남기는 상품의 그 시점 값 (바꾼 전·후). image 는 없으면 null */
public record ProductSnapshot(String name, String description, Money price, boolean onSale, CategoryCode category,
                              ImageKey image) {

    public ProductSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(category, "category");
    }
}
