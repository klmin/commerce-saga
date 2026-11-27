package com.klmin.commerce.inventoryservice.stock.domain;

public enum ReservationStatus {
    /** 모든 항목의 재고를 잡았다 */
    RESERVED,
    /** 하나라도 잡지 못해 아무것도 잡지 않았다 */
    REJECTED,
    /** 보상으로 잡았던 재고를 되돌렸다 (또는 예약 전에 해제 요청이 왔다) */
    RELEASED,
    /** 주문 완료로 잡았던 재고가 판매로 확정됐다 */
    CONFIRMED,
    /** 완료 후 취소로 이 주문의 재고를 가용으로 되돌렸다 (확정 전이었으면 예약을 풀어서, ADR 0020) */
    RETURNED
}
