package com.klmin.commerce.searchservice.product.application.port.out;

import com.klmin.commerce.searchservice.product.domain.SearchCriteria;
import com.klmin.commerce.searchservice.product.domain.SearchPage;

/** 검색 색인 읽기. 저장소가 응답하지 않으면 {@code SearchStoreUnavailableException} */
public interface ProductSearchPort {

    SearchPage search(SearchCriteria criteria);
}
