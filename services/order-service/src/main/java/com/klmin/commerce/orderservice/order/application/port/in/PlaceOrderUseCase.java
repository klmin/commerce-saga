package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.Order;

public interface PlaceOrderUseCase {

    /** 주문(PENDING)과 Saga 를 저장하고, 같은 트랜잭션에서 첫 커맨드(재고 예약)를 outbox 에 남긴다 */
    Order placeOrder(PlaceOrderCommand command);
}
