package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.domain.ImageKey;

/** 저장소 key → 읽기 경로 (ProductController#image) */
final class ProductImagePaths {

    private ProductImagePaths() {
    }

    static String of(String imageKey) {
        if (imageKey == null) {
            return null;
        }
        ImageKey key = ImageKey.of(imageKey);
        return "/products/" + key.productId().value() + "/images/" + key.fileName();
    }
}
