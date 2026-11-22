package com.klmin.commerce.contract.point;

import java.util.UUID;

/**
 * 커맨드(order -> point, 보상): 이 주문으로 차감한 포인트를 돌려줘라.
 * 차감 기록 기준으로 돌려주므로 여러 번 와도, 차감한 적 없는 주문이어도 안전하다 (항상 PointRefunded 응답).
 */
public record RefundPoint(UUID orderId) {

    public static final String TYPE = "RefundPoint";
}
