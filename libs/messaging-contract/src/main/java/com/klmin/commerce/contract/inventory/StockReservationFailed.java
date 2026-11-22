package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/** 응답(inventory -> order): 재고 예약 실패 (재고 부족, 없는 상품 등). 아무 재고도 예약되지 않았다 */
public record StockReservationFailed(UUID orderId, String reason) {

    public static final String TYPE = "StockReservationFailed";
}
