package com.klmin.commerce.searchservice.product.domain;

import com.klmin.commerce.searchservice.common.error.DomainValidationException;

/**
 * 상품 검색 조건.
 *
 * @param keyword    이름·설명 검색어 (없으면 전체). 앞뒤 공백 제거, 최대 {@value #MAX_KEYWORD_LENGTH}자
 * @param minPrice   최소 가격 (없으면 null)
 * @param maxPrice   최대 가격 (없으면 null)
 * @param onSaleOnly 판매 중인 상품만
 * @param sort       정렬
 * @param page       0부터
 * @param size       1~{@value #MAX_SIZE}. 앞에서부터 {@value #MAX_WINDOW}건까지만 넘겨 볼 수 있다
 */
public record SearchCriteria(String keyword, Long minPrice, Long maxPrice, boolean onSaleOnly, SearchSort sort,
                             int page, int size) {

    public static final int MAX_KEYWORD_LENGTH = 100;
    public static final int MAX_SIZE = 100;
    /** Elasticsearch 기본 max_result_window */
    public static final int MAX_WINDOW = 10_000;

    public SearchCriteria {
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new DomainValidationException("검색어는 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다");
        }
        if (minPrice != null && minPrice < 0 || maxPrice != null && maxPrice < 0) {
            throw new DomainValidationException("가격 범위는 0 이상이어야 합니다");
        }
        if (minPrice != null && maxPrice != null && minPrice > maxPrice) {
            throw new DomainValidationException("최소 가격이 최대 가격보다 큽니다");
        }
        if (sort == null) {
            sort = SearchSort.RELEVANCE;
        }
        if (page < 0) {
            throw new DomainValidationException("page 는 0 이상이어야 합니다");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new DomainValidationException("size 는 1~" + MAX_SIZE + " 이어야 합니다");
        }
        if ((long) (page + 1) * size > MAX_WINDOW) {
            throw new DomainValidationException("앞에서부터 " + MAX_WINDOW + "건까지만 볼 수 있습니다. 검색어를 좁혀 주세요");
        }
    }

    public int from() {
        return page * size;
    }
}
