package com.klmin.commerce.orderservice.order.domain;

/**
 * 주문 Saga 단계.
 * <pre>
 * 정방향:  RESERVING_STOCK -> USING_POINT -> PAYING -> COMPLETED
 *          (포인트 0 이면 USING_POINT, 결제 금액 0 이면 PAYING 건너뜀)
 * 보상:    (실패) -> REFUNDING_POINT -> RELEASING_STOCK -> CANCELLED
 *          (결제 결과를 끝내 못 받아 포기하면 CANCELLING_PAYMENT 부터: 결제 취소 -> 포인트 환불 -> 재고 해제)
 *          (포인트를 차감하지 않았으면 REFUNDING_POINT 건너뜀, 재고 예약 실패면 바로 CANCELLED)
 * 사용자 취소 (ADR 0020):
 *          진행 중이면 다음 정방향 응답에서 위 보상으로 (결제 승인이면 CANCELLING_PAYMENT 부터)
 *          완료 후면 COMPLETED -> CANCELLING_PAYMENT -> REFUNDING_POINT -> RETURNING_STOCK -> CANCELLED
 * </pre>
 */
public enum SagaState {
    RESERVING_STOCK,
    USING_POINT,
    PAYING,
    COMPLETED,
    CANCELLING_PAYMENT,
    REFUNDING_POINT,
    RELEASING_STOCK,
    /** 완료 후 취소의 마지막 단계: 팔린 재고를 다시 들인다 (ADR 0020) */
    RETURNING_STOCK,
    CANCELLED;

    public boolean isFinished() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean isCompensating() {
        return this == CANCELLING_PAYMENT || this == REFUNDING_POINT || this == RELEASING_STOCK
                || this == RETURNING_STOCK;
    }
}
