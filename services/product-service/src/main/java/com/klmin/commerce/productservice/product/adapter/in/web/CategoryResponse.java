package com.klmin.commerce.productservice.product.adapter.in.web;

import com.klmin.commerce.productservice.product.application.port.in.CategoryResult;

/** 카테고리 하나 (code 는 상품의 categoryCode·검색 필터 값) */
record CategoryResponse(String code, String name) {

    static CategoryResponse from(CategoryResult r) {
        return new CategoryResponse(r.code(), r.name());
    }
}
