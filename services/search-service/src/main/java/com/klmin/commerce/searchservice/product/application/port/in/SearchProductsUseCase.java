package com.klmin.commerce.searchservice.product.application.port.in;

/** 상품 검색 (공개). 결과는 CDC 로 받은 읽기 모델이라 수 초 늦을 수 있다 */
public interface SearchProductsUseCase {

    ProductSearchResult search(SearchProductsQuery query);
}
