package com.klmin.commerce.searchservice.product.domain;

import java.util.List;

/** 검색 결과 한 페이지와 조건에 맞는 전체 수 */
public record SearchPage(List<SearchableProduct> items, long total) {

    public SearchPage {
        items = List.copyOf(items);
    }
}
