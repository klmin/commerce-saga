package com.klmin.commerce.contract.order;

import java.util.UUID;

/**
 * 이벤트(order.events): 주문이 완료됐다 (재고 예약·포인트 차감·결제 모두 성공).
 * 받는 쪽: inventory(예약 재고 확정), notification 등. 발행자는 누가 받는지 모른다.
 */
public record OrderCompleted(UUID orderId, UUID userId, long totalAmount, long pointAmount, long paidAmount) {

    public static final String TYPE = "OrderCompleted";
}
