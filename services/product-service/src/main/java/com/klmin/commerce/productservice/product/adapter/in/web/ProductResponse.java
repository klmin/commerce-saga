package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.ProductResult;

import java.util.UUID;

/**
 * onSale=false 면 주문할 수 없다 (order 가 422). categoryCode 의 이름은 GET /products/categories (ADR 0021).
 * imagePath 는 대표 이미지를 읽는 이 서비스의 경로(/products/{id}/images/{파일}), 없으면 null.
 * 화면은 API 앞부분(Gateway /api)을 붙여 부른다. 저장소 주소는 내보내지 않는다
 */
record ProductResponse(UUID id, String name, String description, long price, boolean onSale, String categoryCode,
                       String imagePath) {

    static ProductResponse from(ProductResult r) {
        return new ProductResponse(r.id(), r.name(), r.description(), r.price(), r.onSale(), r.categoryCode(),
                ProductImagePaths.of(r.imageKey()));
    }
}
