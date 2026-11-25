package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.domain.ProductChange;

/** 관리 이력 저장 (상품을 바꾼 트랜잭션 안에서) */
public interface ProductAuditPort {

    void save(ProductChange change);
}
