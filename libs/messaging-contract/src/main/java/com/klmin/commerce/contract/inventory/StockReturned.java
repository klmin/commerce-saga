package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/** 응답(inventory -> order): 재고 반환 완료 (되돌릴 것이 없었던 경우 포함, ADR 0020) */
public record StockReturned(UUID orderId) {

    public static final String TYPE = "StockReturned";
}
