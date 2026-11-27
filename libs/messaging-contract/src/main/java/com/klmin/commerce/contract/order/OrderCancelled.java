package com.klmin.commerce.contract.order;

import java.util.UUID;

/**
 * 이벤트(order.events): 주문이 취소됐다 (실패 후 보상까지 끝남, 또는 사용자 취소). reason 은 처음 실패한 단계의 사유
 * 또는 사용자 취소 사유.
 *
 * @param afterCompletion 완료됐던 주문을 사용자가 취소했으면 true (ADR 0020, 추가한 필드). 이 필드가 없는 옛 메시지도
 *                        읽혀야 하므로 {@code Boolean} 으로 받고(없으면 null) 생성할 때 false 로 맞춘다. 원시 boolean 이면
 *                        Jackson 3 기본 설정(FAIL_ON_NULL_FOR_PRIMITIVES)에서 옛 메시지를 읽지 못한다
 */
public record OrderCancelled(UUID orderId, UUID userId, String reason, Boolean afterCompletion) {

    public static final String TYPE = "OrderCancelled";

    public OrderCancelled {
        afterCompletion = Boolean.TRUE.equals(afterCompletion);
    }

    /** 완료 전 취소 (afterCompletion = false) */
    public OrderCancelled(UUID orderId, UUID userId, String reason) {
        this(orderId, userId, reason, false);
    }
}
