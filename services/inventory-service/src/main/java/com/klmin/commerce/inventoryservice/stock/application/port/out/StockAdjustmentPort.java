package com.klmin.commerce.inventoryservice.stock.application.port.out;

import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.StockAdjustment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 관리자 재고 조정 이력 (추가 전용) */
public interface StockAdjustmentPort {

    Optional<StockAdjustment> findByKey(UUID adjustedBy, String idempotencyKey);

    /**
     * 기록을 남기고 id 를 채운 기록을 돌려준다. 같은 관리자·같은 키가 이미 있으면(동시에 같은 키로 다른 상품을 조정한 경우)
     * 아무것도 넣지 않고 빈 값
     */
    Optional<StockAdjustment> insert(StockAdjustment adjustment);

    /** 최근부터. hasNext 판단을 위해 limit 개까지 */
    List<StockAdjustment> findByProduct(ProductId productId, int offset, int limit);
}
