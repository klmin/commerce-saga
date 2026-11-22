package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/** 응답(inventory -> order): 재고 해제 완료 (해제할 것이 없었던 경우 포함) */
public record StockReleased(UUID orderId) {

    public static final String TYPE = "StockReleased";
}
