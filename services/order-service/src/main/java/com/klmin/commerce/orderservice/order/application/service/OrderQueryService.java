package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersQuery;
import com.klmin.commerce.orderservice.order.application.port.in.ListMyOrdersUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.OrderSummaryResult;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderSummariesPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 주문 목록 조회. 쓰기가 아니라 애그리거트를 거치지 않고 DTO 로 바로 읽는다 */
@Service
class OrderQueryService implements ListMyOrdersUseCase {

    private final LoadOrderSummariesPort summariesPort;

    OrderQueryService(LoadOrderSummariesPort summariesPort) {
        this.summariesPort = summariesPort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSummaryResult> listMyOrders(ListMyOrdersQuery query) {
        return summariesPort.findByUser(query.userId(), query.before(), query.size());
    }
}
