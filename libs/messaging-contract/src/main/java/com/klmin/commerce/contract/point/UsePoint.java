package com.klmin.commerce.contract.point;

import java.util.UUID;

/** 커맨드(order -> point): 사용자 포인트를 이 주문에 amount 만큼 차감하라. 잔액이 부족하면 차감하지 않는다 */
public record UsePoint(UUID orderId, UUID userId, long amount) {

    public static final String TYPE = "UsePoint";
}
