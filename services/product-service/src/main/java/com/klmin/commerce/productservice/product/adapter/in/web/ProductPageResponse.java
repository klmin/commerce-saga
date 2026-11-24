package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;

import java.util.List;

record ProductPageResponse(List<ProductResponse> items, boolean hasNext) {

    static ProductPageResponse from(ProductPageResult r) {
        return new ProductPageResponse(r.items().stream().map(ProductResponse::from).toList(), r.hasNext());
    }
}
