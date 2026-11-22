package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.UUID;

/** inventory-service 의 재고 예약 응답 처리. 같은 응답이 여러 번 와도 결과가 같다 */
public interface HandleStockReplyUseCase {

    void stockReserved(UUID orderId);

    void stockReservationFailed(UUID orderId, String reason);
}
