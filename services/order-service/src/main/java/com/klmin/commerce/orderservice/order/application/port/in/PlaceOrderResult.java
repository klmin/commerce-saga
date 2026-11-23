package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.Order;

/** @param created 이번 요청으로 만들었으면 true, 같은 Idempotency-Key 의 처음 주문을 돌려주면 false */
public record PlaceOrderResult(Order order, boolean created) {
}
