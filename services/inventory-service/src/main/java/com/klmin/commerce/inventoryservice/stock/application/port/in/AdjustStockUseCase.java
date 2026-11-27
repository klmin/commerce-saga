package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.UUID;

/** 관리자 재고 조정: 입고·차감과 그 이력 (ADR 0019) */
public interface AdjustStockUseCase {

    int MAX_PAGE_SIZE = 100;
    int MAX_PAGE = 10_000;

    /**
     * 재고 행이 없으면(새로 등록한 상품) 0 으로 만든 뒤 조정한다. 같은 관리자가 같은 키로 다시 보내면 처음 결과를 돌려준다.
     *
     * @throws com.klmin.commerce.inventoryservice.common.error.ConflictException 가용 재고보다 많이 차감 (409)
     * @throws com.klmin.commerce.inventoryservice.stock.application.IdempotencyKeyReusedException 같은 키, 다른 내용 (422)
     */
    AdjustStockResult adjust(AdjustStockCommand command);

    /** 상품의 조정 이력 (최근부터). 재고 행이 없으면 빈 목록 */
    StockAdjustmentPageResult adjustments(UUID productId, int page, int size);
}
