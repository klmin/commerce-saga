package com.klmin.commerce.orderservice.order.domain;

/**
 * 주문 Saga 단계.
 * <pre>
 * 정방향:  RESERVING_STOCK -> USING_POINT -> PAYING -> COMPLETED
 *          (포인트 0 이면 USING_POINT, 결제 금액 0 이면 PAYING 건너뜀)
 * 보상:    (실패) -> REFUNDING_POINT -> RELEASING_STOCK -> CANCELLED
 *          (포인트를 차감하지 않았으면 REFUNDING_POINT 건너뜀, 재고 예약 실패면 바로 CANCELLED)
 * </pre>
 */
public enum SagaState {
    RESERVING_STOCK,
    USING_POINT,
    PAYING,
    COMPLETED,
    REFUNDING_POINT,
    RELEASING_STOCK,
    CANCELLED;

    public boolean isFinished() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isCompensating() {
        return this == REFUNDING_POINT || this == RELEASING_STOCK;
    }
}
