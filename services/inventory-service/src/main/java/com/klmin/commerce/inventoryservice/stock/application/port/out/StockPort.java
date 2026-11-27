package com.klmin.commerce.inventoryservice.stock.application.port.out;

import com.klmin.commerce.inventoryservice.stock.domain.ProductId;
import com.klmin.commerce.inventoryservice.stock.domain.Stock;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockPort {

    Optional<Stock> findById(ProductId productId);

    /**
     * 재고 행을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다. 트랜잭션이 끝날 때까지 다른 예약이 기다린다.
     * 교착을 피하려고 productId 순서로 잠근다. 없는 상품은 결과에서 빠진다.
     */
    List<Stock> findAllForUpdate(Collection<ProductId> productIds);

    void update(Collection<Stock> stocks);

    /**
     * 재고 행이 없으면 0 으로 만든다(관리자가 새 상품에 처음 입고할 때, ADR 0019). 이미 있으면 그대로.
     * 동시에 두 트랜잭션이 만들려 해도 하나만 들어간다(뒤는 앞이 끝날 때까지 기다렸다 건너뜀)
     */
    void createIfAbsent(ProductId productId, Instant now);
}
