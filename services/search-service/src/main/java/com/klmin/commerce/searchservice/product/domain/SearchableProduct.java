package com.klmin.commerce.searchservice.product.domain;

import com.klmin.commerce.searchservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * 검색 읽기 모델의 상품 (ADR 0010). product-service 의 products 행을 CDC 로 받아 만든 사본이다.
 * 주인은 product-service 이고 여기서는 바꾸지 않는다. 가격은 안내용이며 주문 때 다시 확인한다 (ADR 0016)
 *
 * @param category 카테고리 코드 (ADR 0021). 열을 추가하기 전에 색인된 행·옛 메시지는 null
 * @param imageKey 대표 이미지의 저장소 key (ADR 0021). 없으면 null. 응답에는 읽기 경로로 바꿔 내보낸다
 */
public record SearchableProduct(UUID id, String name, String description, long price, boolean onSale,
                                Instant updatedAt, String category, String imageKey) {

    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_CATEGORY_LENGTH = 30;
    public static final int MAX_IMAGE_KEY_LENGTH = 200;

    public SearchableProduct {
        if (id == null) {
            throw new DomainValidationException("상품 id 가 없습니다");
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
        category = blankToNull(category);
        if (category != null && category.length() > MAX_CATEGORY_LENGTH) {
            throw new DomainValidationException("카테고리 코드가 너무 깁니다 (id=" + id + ")");
        }
        imageKey = blankToNull(imageKey);
        if (imageKey != null && imageKey.length() > MAX_IMAGE_KEY_LENGTH) {
            throw new DomainValidationException("이미지 key 가 너무 깁니다 (id=" + id + ")");
        }
    }

    /** 카테고리·이미지를 모르는 곳(옛 메시지·테스트)용 */
    public SearchableProduct(UUID id, String name, String description, long price, boolean onSale,
                             Instant updatedAt) {
        this(id, name, description, price, onSale, updatedAt, null, null);
    }

    /**
     * 화면이 읽을 이미지 경로(product-service 의 {@code /products/{id}/images/{파일}}, 화면이 {@code /api} 를 붙인다).
     * key 가 이 상품의 것이 아니거나 모양이 다르면 null (저장소 key 를 그대로 내보내지 않는다)
     */
    public String imagePath() {
        String prefix = "products/" + id + "/";
        if (imageKey == null || !imageKey.startsWith(prefix)) {
            return null;
        }
        String fileName = imageKey.substring(prefix.length());
        if (!fileName.matches("[0-9a-z-]{1,40}[.](jpg|png|webp|svg)")) {
            return null;
        }
        return "/products/" + id + "/images/" + fileName;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
