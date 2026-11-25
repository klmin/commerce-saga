package com.klmin.commerce.productservice.product.domain;

/** 관리 이력의 변경 종류 (DB product_audit_log.action) */
public enum ProductAction {
    REGISTERED,
    DESCRIBED,
    PRICE_CHANGED,
    SALE_STATUS_CHANGED
}
