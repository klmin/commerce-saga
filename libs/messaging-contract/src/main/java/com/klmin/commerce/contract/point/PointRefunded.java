package com.klmin.commerce.contract.point;

import java.util.UUID;

/** 응답(point -> order): 포인트 환불 완료 (환불할 것이 없었던 경우 포함) */
public record PointRefunded(UUID orderId) {

    public static final String TYPE = "PointRefunded";
}
