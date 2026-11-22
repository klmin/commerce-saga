package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/** 응답(inventory -> order): 재고 예약 성공 */
public record StockReserved(UUID orderId) {

    public static final String TYPE = "StockReserved";
}
