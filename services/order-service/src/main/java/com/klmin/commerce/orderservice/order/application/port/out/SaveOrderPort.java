package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.domain.Order;

public interface SaveOrderPort {

    void insert(Order order);

    /** 상태 변경을 반영한다. 동시에 다른 트랜잭션이 바꿨으면 낙관적 락 예외 */
    void update(Order order);
}
