package com.klmin.commerce.orderservice.order.domain;

/**
 * 주문 상태 (사용자에게 보이는 결과). 진행 단계는 {@link OrderSaga} 가 따로 가진다.
 */
public enum OrderStatus {
    /** Saga 진행 중 */
    PENDING,
    /** 재고 예약·포인트 차감·결제 모두 성공 */
    COMPLETED,
    /** 실패 후 보상까지 끝남 */
    CANCELLED
}
