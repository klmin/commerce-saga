package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.Order;

/**
 * @param inProgress 취소가 접수돼 진행 중이면 true (응답 202). 이미 취소가 끝난 주문이면 false (응답 200)
 */
public record CancelOrderResult(Order order, boolean inProgress) {
}
