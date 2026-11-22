package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.Order;

/** 주문 결과 이벤트(order.events) 발행. 구현은 outbox 저장이라 호출한 트랜잭션과 함께 커밋된다 */
public interface OrderEventPort {

    void orderCompleted(Order order);

    void orderCancelled(Order order);
}
