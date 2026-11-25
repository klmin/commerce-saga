package com.klmin.commerce.searchservice.product.domain;

import com.klmin.commerce.searchservice.common.error.DomainValidationException;

import java.time.Instant;

/**
 * 검색 읽기 모델의 상품 (ADR 0010). product-service 의 products 행을 CDC 로 받아 만든 사본이다.
 * 주인은 product-service 이고 여기서는 바꾸지 않는다. 가격은 안내용이며 주문 때 다시 확인한다 (ADR 0016)
 */
public record SearchableProduct(long id, String name, String description, long price, boolean onSale,
                                Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    public SearchableProduct {
        if (id <= 0) {
            throw new DomainValidationException("상품 id 는 양수여야 합니다: " + id);
        }
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new DomainValidationException("상품 이름이 비었거나 너무 깁니다 (id=" + id + ")");
        }
        description = description == null ? "" : description;
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new DomainValidationException("상품 설명이 너무 깁니다 (id=" + id + ")");
        }
        if (price < 0) {
            throw new DomainValidationException("가격은 0 이상이어야 합니다 (id=" + id + ")");
        }
        if (updatedAt == null) {
            throw new DomainValidationException("수정 시각이 없습니다 (id=" + id + ")");
        }
    }
}
