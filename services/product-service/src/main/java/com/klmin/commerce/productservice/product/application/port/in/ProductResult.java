package com.klmin.commerce.productservice.product.application.port.in;

import com.klmin.commerce.productservice.product.domain.Product;

import java.time.Instant;

public record ProductResult(long id, String name, String description, long price, boolean onSale,
                            Instant updatedAt) {

    public static ProductResult from(Product product) {
        return new ProductResult(product.id().value(), product.name(), product.description(),
                product.price().value(), product.onSale(), product.updatedAt());
    }
}
