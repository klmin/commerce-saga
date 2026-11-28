package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.application.port.in.CategoryResult;
import com.klmin.commerce.productservice.product.domain.CategoryCode;

import java.util.List;

/** 카테고리 고정 목록 (categories 테이블, ADR 0021) */
public interface CategoryPort {

    boolean exists(CategoryCode code);

    /** 정해 둔 순서(sort_order) */
    List<CategoryResult> findAll();
}
