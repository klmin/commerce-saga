package com.klmin.commerce.productservice.product.application.port.out;

import com.klmin.commerce.productservice.product.application.port.in.ListProductsQuery;
import com.klmin.commerce.productservice.product.application.port.in.PriceChangeResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductAuditPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductPageResult;
import com.klmin.commerce.productservice.product.application.port.in.ProductResult;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 조회 전용 (애그리거트를 거치지 않고 DTO 로) */
public interface LoadProductPort {

    Optional<ProductResult> findById(UUID productId);

    ProductPageResult list(ListProductsQuery query);

    List<ProductResult> findByIds(Collection<UUID> productIds);

    boolean exists(UUID productId);

    List<PriceChangeResult> priceHistory(UUID productId);

    /** 관리 이력, 최근 변경부터 */
    ProductAuditPageResult auditLog(UUID productId, int page, int size);
}
