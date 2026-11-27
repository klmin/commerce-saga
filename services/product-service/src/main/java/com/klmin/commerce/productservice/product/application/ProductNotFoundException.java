package com.klmin.commerce.productservice.product.application;

import com.klmin.commerce.productservice.common.error.NotFoundException;

import java.util.UUID;

public class ProductNotFoundException extends NotFoundException {

    public ProductNotFoundException(UUID productId) {
        super("상품이 없습니다: " + productId);
    }
}
