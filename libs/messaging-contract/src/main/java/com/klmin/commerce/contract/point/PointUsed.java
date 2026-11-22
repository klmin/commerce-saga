package com.klmin.commerce.contract.point;

import java.util.UUID;

/** 응답(point -> order): 포인트 차감 성공 */
public record PointUsed(UUID orderId) {

    public static final String TYPE = "PointUsed";
}
