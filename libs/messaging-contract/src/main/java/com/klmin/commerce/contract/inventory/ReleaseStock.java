package com.klmin.commerce.contract.inventory;

import java.util.UUID;

/**
 * 커맨드(order -> inventory, 보상): 이 주문으로 잡은 재고를 되돌려라.
 * 예약 기록 기준으로 되돌리므로 여러 번 와도, 예약이 없거나 거절됐던 주문이어도 안전하다 (항상 StockReleased 응답).
 */
public record ReleaseStock(UUID orderId) {

    public static final String TYPE = "ReleaseStock";
}
