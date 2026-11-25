package com.klmin.commerce.searchservice.product.application.port.in;

/**
 * 상품 변경(CDC)을 검색 색인에 반영한다 (ADR 0010). 같은 변경이 다시 오거나 옛 변경이 늦게 와도
 * 버전(원본 LSN)으로 걸러지므로 여러 번 불러도 결과가 같다
 */
public interface IndexProductUseCase {

    /** @return 반영했으면 true. 같은·옛 버전이라 건너뛰었으면 false */
    boolean index(IndexProductCommand command);

    /** @return 지웠으면 true. 없거나 옛 버전이면 false */
    boolean remove(RemoveProductCommand command);
}
