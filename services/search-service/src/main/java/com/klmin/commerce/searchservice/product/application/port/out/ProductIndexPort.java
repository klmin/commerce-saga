package com.klmin.commerce.searchservice.product.application.port.out;

import com.klmin.commerce.searchservice.product.domain.SearchableProduct;

/** 검색 색인 쓰기. 버전은 외부 버전(원본 LSN): 저장된 것보다 크지 않으면 바꾸지 않는다 */
public interface ProductIndexPort {

    /** @return 저장했으면 true, 같은·옛 버전이라 건너뛰었으면 false */
    boolean save(SearchableProduct product, long version);

    /**
     * @param version null 이면 버전 없이 지운다
     * @return 지웠으면 true, 없거나 옛 버전이면 false
     */
    boolean delete(long productId, Long version);
}
