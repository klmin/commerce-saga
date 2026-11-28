package com.klmin.commerce.inventoryservice.stock.application.port.in;

import java.util.UUID;

public interface ReturnStockUseCase {

    /**
     * 완료 후 취소 (ADR 0020): 이 주문으로 팔린 재고를 가용으로 다시 들이고 StockReturned 를 응답한다 (같은 트랜잭션에서 outbox 저장).
     * 아직 판매 확정 전이면 예약을 푼다. 같은 messageId 는 한 번만 처리한다. 다른 메시지로 여러 번 와도, 되돌릴 것이 없어도
     * 재고는 한 번만 되돌리고 항상 응답한다.
     */
    void returnStock(UUID messageId, UUID orderId);
}
