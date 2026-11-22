package com.klmin.commerce.contract.point;

import java.util.UUID;

/** 응답(point -> order): 포인트 차감 실패 (잔액 부족 등). 차감되지 않았다 */
public record PointUseFailed(UUID orderId, String reason) {

    public static final String TYPE = "PointUseFailed";
}
