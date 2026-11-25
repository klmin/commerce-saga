package com.klmin.commerce.searchservice.product.domain;

import com.klmin.commerce.searchservice.common.error.DomainValidationException;

import java.util.Locale;

/** 검색 결과 정렬. 같은 값이면 상품 id 오름차순으로 순서를 고정한다 */
public enum SearchSort {
    RELEVANCE, PRICE_ASC, PRICE_DESC, NEWEST;

    /** 비었으면 관련도순. 대소문자·하이픈 무시 (예: price-asc) */
    public static SearchSort parse(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("정렬은 relevance, price-asc, price-desc, newest 중 하나입니다: " + value);
        }
    }
}
