package com.klmin.commerce.productservice.product.application;

import com.klmin.commerce.productservice.common.error.NotFoundException;

public class ProductImageNotFoundException extends NotFoundException {

    public ProductImageNotFoundException() {
        super("이미지가 없습니다.");
    }
}
