package com.klmin.commerce.inventoryservice.stock.domain;

public enum ReservationStatus {
    /** 모든 항목의 재고를 잡았다 */
    RESERVED,
    /** 하나라도 잡지 못해 아무것도 잡지 않았다 */
    REJECTED
}
