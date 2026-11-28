package com.klmin.commerce.searchservice.product.application.port.in;

/**
 * @param sort     relevance(기본), price-asc, price-desc, newest
 * @param category 카테고리 코드 (없으면 전체, ADR 0021)
 */
public record SearchProductsQuery(String keyword, Long minPrice, Long maxPrice, boolean onSaleOnly, String sort,
                                  int page, int size, String category) {

    public SearchProductsQuery(String keyword, Long minPrice, Long maxPrice, boolean onSaleOnly, String sort,
                               int page, int size) {
        this(keyword, minPrice, maxPrice, onSaleOnly, sort, page, size, null);
    }
}
