package com.klmin.commerce.paymentservice.payment.domain;

public enum PaymentStatus {
    /** PG 승인 */
    APPROVED,
    /** PG 거절 (돈이 나가지 않음) */
    DECLINED,
    /** PG 응답 타임아웃. 승인됐을 수 있어 PG 조회로 확정할 때까지 응답하지 않는다 (ADR 0007) */
    UNKNOWN,
    /** 결제 전에 주문 쪽이 취소함 (돈이 나가지 않음). 늦게 온 Pay 는 결제하지 않는다 */
    CANCELLED,
    /** 승인 후 주문 쪽 취소로 PG 환불까지 끝남 */
    REFUNDED
}
