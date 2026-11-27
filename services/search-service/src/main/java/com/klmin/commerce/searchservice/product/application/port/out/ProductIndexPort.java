package com.klmin.commerce.searchservice.product.application.port.out;

import com.klmin.commerce.searchservice.product.domain.SearchableProduct;

import java.util.UUID;

/** 검색 색인 쓰기. 버전은 외부 버전(원본 LSN): 저장된 것보다 크지 않으면 바꾸지 않는다 */
public interface ProductIndexPort {

    /** @return 저장했으면 true, 같은·옛 버전이라 건너뛰었으면 false */
    boolean save(SearchableProduct product, long version);

    /**
     * 검색에서 빼고, 그 뒤에 늦게 온 옛 변경이 되살리지 못하게 버전을 남긴다(삭제 표시).
     *
     * @param version null 이면 영구히(가장 큰 버전으로) 막는다
     * @return 표시했으면 true, 옛 버전이면 false
     */
    boolean delete(UUID productId, Long version);
}
