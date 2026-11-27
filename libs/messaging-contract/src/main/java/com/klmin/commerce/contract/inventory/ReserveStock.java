package com.klmin.commerce.contract.inventory;

import java.util.List;
import java.util.UUID;

/** 커맨드(order -> inventory): 주문 상품의 재고를 예약하라. 전부 예약되거나 하나도 예약되지 않는다. */
public record ReserveStock(UUID orderId, List<Line> lines) {

    public static final String TYPE = "ReserveStock";

    public ReserveStock {
        lines = List.copyOf(lines);
    }

    public record Line(UUID productId, int quantity) {
    }
}
