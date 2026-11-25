package com.klmin.commerce.searchservice.product.application;

import com.klmin.commerce.searchservice.common.error.ServiceUnavailableException;

/** 검색 저장소(Elasticsearch)가 응답하지 않는다 → 검색 503. 상품 목록·주문은 영향이 없다 (ADR 0010) */
public class SearchStoreUnavailableException extends ServiceUnavailableException {

    public SearchStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
