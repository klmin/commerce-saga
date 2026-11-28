package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.ProductResult;

import java.util.UUID;

/** onSale=false 면 주문할 수 없다 (order 가 422). categoryCode 의 이름은 GET /products/categories (ADR 0021) */
record ProductResponse(UUID id, String name, String description, long price, boolean onSale, String categoryCode) {

    static ProductResponse from(ProductResult r) {
        return new ProductResponse(r.id(), r.name(), r.description(), r.price(), r.onSale(), r.categoryCode());
    }
}
