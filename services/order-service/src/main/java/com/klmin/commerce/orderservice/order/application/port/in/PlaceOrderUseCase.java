package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.Order;

public interface PlaceOrderUseCase {

    /** 주문을 PENDING 으로 저장하고, 같은 트랜잭션에서 재고 예약 커맨드를 outbox 에 남긴다 */
    Order placeOrder(PlaceOrderCommand command);
}
