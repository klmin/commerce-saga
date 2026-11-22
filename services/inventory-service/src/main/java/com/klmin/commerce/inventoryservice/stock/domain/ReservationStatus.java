package com.klmin.commerce.inventoryservice.stock.domain;

public enum ReservationStatus {
    /** 모든 항목의 재고를 잡았다 */
    RESERVED,
    /** 하나라도 잡지 못해 아무것도 잡지 않았다 */
    REJECTED,
    /** 보상으로 잡았던 재고를 되돌렸다 (또는 예약 전에 해제 요청이 왔다) */
    RELEASED,
    /** 주문 완료로 잡았던 재고가 판매로 확정됐다 */
    CONFIRMED
}
