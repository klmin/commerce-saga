package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.Order;

import java.util.UUID;

/** 참여 서비스에 커맨드를 보낸다. 구현은 outbox 저장이라 호출한 트랜잭션과 함께 커밋된다 */
public interface SagaCommandPort {

    void reserveStock(Order order);

    void releaseStock(UUID orderId);

    void usePoint(UUID orderId, long userId, long amount);

    void refundPoint(UUID orderId);

    void pay(UUID orderId, long userId, long amount);
}
