package com.klmin.commerce.orderservice.order.application.port.in;

import java.util.List;

public interface ListMyOrdersUseCase {

    /** 요청한 사용자의 주문만 최신순으로 */
    List<OrderSummaryResult> listMyOrders(ListMyOrdersQuery query);
}
