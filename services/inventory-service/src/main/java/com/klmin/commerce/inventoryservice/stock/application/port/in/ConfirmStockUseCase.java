package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.UUID;

public interface ConfirmStockUseCase {

    /** 주문 완료(OrderCompleted 이벤트): 잡아 둔 재고를 판매로 확정한다. 여러 번 와도 한 번만 반영한다 */
    void confirm(UUID orderId);
}
