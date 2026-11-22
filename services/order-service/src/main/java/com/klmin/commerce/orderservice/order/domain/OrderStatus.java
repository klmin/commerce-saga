package com.klmin.commerce.orderservice.order.domain;

/**
 * 주문 상태. 1단계는 재고 예약 결과만 반영한다.
 * 2단계에서 포인트·결제 단계와 보상 상태가 Saga 상태 머신으로 추가된다.
 */
public enum OrderStatus {
    /** 생성됨. 재고 예약 응답을 기다리는 중 */
    PENDING,
    /** 재고 예약 성공 */
    CONFIRMED,
    /** 재고 예약 실패 */
    REJECTED
}
