package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.Order;

/** inventory-service 에 재고 예약을 요청한다. 구현은 outbox 에 저장만 하므로 호출한 트랜잭션과 함께 커밋된다 */
public interface RequestStockReservationPort {

    void requestReservation(Order order);
}
