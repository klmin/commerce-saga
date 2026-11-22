package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.OrderSaga;

import java.util.Optional;
import java.util.UUID;

public interface OrderSagaPort {

    Optional<OrderSaga> findByOrderId(UUID orderId);

    /**
     * Saga 행을 비관적 락(SELECT ... FOR UPDATE)으로 잠가 가져온다. 응답은 세 토픽(inventory/point/payment.reply)의
     * 서로 다른 리스너로 오므로, 같은 주문의 응답 처리를 이 락으로 줄 세운다.
     */
    Optional<OrderSaga> findByOrderIdForUpdate(UUID orderId);

    void insert(OrderSaga saga);

    void update(OrderSaga saga);
}
