package com.klmin.commerce.productservice.product.application.port.in;

import java.util.List;

public interface GetProductUseCase {

    int MAX_IDS = 50;
    int MAX_PAGE_SIZE = 100;

    ProductResult get(long productId);

    ProductPageResult list(ListProductsQuery query);

    /**
     * 여러 상품을 한 번에 (order-service 가 주문·장바구니 때 부른다, ADR 0016). 판매 중지 상품도 돌려준다.
     * 없는 id 는 결과에서 빠진다. id 순서
     */
    List<ProductResult> findByIds(List<Long> productIds);

    /** 최근 변경부터 */
    List<PriceChangeResult> priceHistory(long productId);

    /** 관리 이력 (최근 변경부터, 페이지). 없는 상품이면 404 */
    ProductAuditPageResult auditLog(long productId, int page, int size);
}
