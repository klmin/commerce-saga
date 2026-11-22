package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.UUID;

public interface ReleaseStockUseCase {

    /**
     * 보상: 이 주문으로 잡은 재고를 되돌리고 StockReleased 를 응답한다 (같은 트랜잭션에서 outbox 저장).
     * 같은 messageId 는 한 번만 처리한다. 다른 메시지로 여러 번 와도, 예약이 없거나 거절됐던 주문이어도 재고는 한 번만 되돌리고 항상 응답한다.
     */
    void release(UUID messageId, UUID orderId);
}
