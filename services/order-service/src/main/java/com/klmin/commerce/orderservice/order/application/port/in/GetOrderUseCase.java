package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.Order;

public interface GetOrderUseCase {

    /** 없거나 다른 사용자의 주문이면 OrderNotFoundException (존재 여부를 드러내지 않는다) */
    Order getOrder(GetOrderQuery query);
}
