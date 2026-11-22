package com.klmin.commerce.contract.order;

import java.util.UUID;

/** 이벤트(order.events): 주문이 취소됐다 (실패 후 보상까지 끝남). reason 은 처음 실패한 단계의 사유 */
public record OrderCancelled(UUID orderId, long userId, String reason) {

    public static final String TYPE = "OrderCancelled";
}
