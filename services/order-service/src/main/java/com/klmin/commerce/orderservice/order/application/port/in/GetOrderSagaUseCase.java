package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.OrderSaga;

/**
 * 주문 Saga 진행 상태 조회. 본인 주문만 볼 수 있다 (없거나 남의 주문이면 SagaNotFoundException, 존재 여부를 숨김).
 * 관리자 전체 조회는 4단계(ADMIN 역할)에서 추가한다.
 */
public interface GetOrderSagaUseCase {

    OrderSaga getSaga(GetOrderQuery query);
}
