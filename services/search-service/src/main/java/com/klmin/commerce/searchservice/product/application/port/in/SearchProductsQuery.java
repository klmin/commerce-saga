package com.klmin.commerce.searchservice.product.application.port.in;

/**
 * @param sort relevance(기본), price-asc, price-desc, newest
 */
public record SearchProductsQuery(String keyword, Long minPrice, Long maxPrice, boolean onSaleOnly, String sort,
                                  int page, int size) {
}
