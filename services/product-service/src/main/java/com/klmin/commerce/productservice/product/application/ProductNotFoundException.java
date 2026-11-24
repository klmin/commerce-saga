package com.klmin.commerce.productservice.product.application;

import com.klmin.commerce.productservice.common.error.NotFoundException;

public class ProductNotFoundException extends NotFoundException {

    public ProductNotFoundException(long productId) {
        super("상품이 없습니다: " + productId);
    }
}
