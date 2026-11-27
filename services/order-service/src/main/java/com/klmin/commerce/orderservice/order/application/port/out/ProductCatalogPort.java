package com.klmin.commerce.orderservice.order.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 상품의 지금 가격 (product-service 동기 조회, ADR 0016). 주문 API 요청 안에서 DB 트랜잭션 밖에서만 부른다.
 * Saga 리스너·스케줄러에서는 부르지 않는다(가격은 주문 행의 스냅샷만 쓴다).
 */
public interface ProductCatalogPort {

    /**
     * 없는 상품은 결과에서 빠진다.
     *
     * @throws com.klmin.commerce.orderservice.order.application.ProductCatalogUnavailableException 응답이 없거나(타임아웃) 오류
     */
    List<CatalogProduct> findByIds(Collection<UUID> productIds);

    record CatalogProduct(UUID productId, String name, long price, boolean onSale) {
    }
}
