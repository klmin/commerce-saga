package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.List;

/** 조정 이력 한 쪽 (최근부터) */
public record StockAdjustmentPageResult(List<AdjustStockResult.Adjustment> items, boolean hasNext) {

    public StockAdjustmentPageResult {
        items = List.copyOf(items);
    }
}
