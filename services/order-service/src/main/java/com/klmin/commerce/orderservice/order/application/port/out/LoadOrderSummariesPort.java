package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;

import java.util.List;
import java.util.UUID;

public interface LoadOrderSummariesPort {

    /** 사용자의 주문을 최신순(id 내림차순)으로. before 가 있으면 그보다 오래된 것만 */
    List<OrderSummaryResult> findByUser(long userId, UUID before, int size);
}
