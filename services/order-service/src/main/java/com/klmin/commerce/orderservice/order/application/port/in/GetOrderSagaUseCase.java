package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.OrderSaga;

import java.util.UUID;

/** 관리자용 Saga 진행 상태 조회 (권한 검사는 4단계 ADMIN 역할) */
public interface GetOrderSagaUseCase {

    OrderSaga getSaga(UUID orderId);
}
