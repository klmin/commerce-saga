package com.klmin.commerce.contract.payment;

import java.util.UUID;

/**
 * 응답(payment -> order): 결제 취소를 접수했다. 이후 이 주문으로는 돈이 나가지 않거나, 나갔다면 결제 서비스가 환불한다.
 * Saga 는 이 응답을 받으면 나머지 보상(포인트 환불, 재고 해제)으로 넘어간다
 */
public record PaymentCancelled(UUID orderId) {

    public static final String TYPE = "PaymentCancelled";
}
