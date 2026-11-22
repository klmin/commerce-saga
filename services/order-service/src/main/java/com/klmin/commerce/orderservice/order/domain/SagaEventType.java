package com.klmin.commerce.orderservice.order.domain;

/** Saga 를 진행시키는 참여 서비스 응답 */
public enum SagaEventType {
    STOCK_RESERVED,
    STOCK_RESERVATION_FAILED,
    STOCK_RELEASED,
    POINT_USED,
    POINT_USE_FAILED,
    POINT_REFUNDED,
    PAYMENT_APPROVED,
    PAYMENT_FAILED
}
