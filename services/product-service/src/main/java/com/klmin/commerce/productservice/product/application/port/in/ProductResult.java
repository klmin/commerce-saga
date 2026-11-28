package com.klmin.commerce.productservice.product.application.port.in;

import com.klmin.commerce.productservice.product.domain.Product;

import java.time.Instant;
import java.util.UUID;

/** imageKey 는 대표 이미지의 저장소 key (없으면 null) */
public record ProductResult(UUID id, String name, String description, long price, boolean onSale,
                            String categoryCode, String imageKey, Instant updatedAt) {

    public static ProductResult from(Product product) {
        return new ProductResult(product.id().value(), product.name(), product.description(),
                product.price().value(), product.onSale(), product.category().value(),
                product.image() == null ? null : product.image().value(), product.updatedAt());
    }
}
