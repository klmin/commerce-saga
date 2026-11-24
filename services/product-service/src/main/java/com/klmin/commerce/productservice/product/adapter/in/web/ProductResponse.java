package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.ProductResult;

/** onSale=false 면 주문할 수 없다 (order 가 422) */
record ProductResponse(long id, String name, String description, long price, boolean onSale) {

    static ProductResponse from(ProductResult r) {
        return new ProductResponse(r.id(), r.name(), r.description(), r.price(), r.onSale());
    }
}
