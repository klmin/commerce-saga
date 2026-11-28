package com.klmin.commerce.inventoryservice.stock.application.port.out;

import java.util.UUID;

/** 오케스트레이터(order-service)에 결과를 응답한다. 구현은 outbox 저장이라 호출한 트랜잭션과 함께 커밋된다 */
public interface SendStockReplyPort {

    void stockReserved(UUID orderId);

    void stockReservationFailed(UUID orderId, String reason);

    void stockReleased(UUID orderId);

    void stockReturned(UUID orderId);
}
