package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.UUID;

/** 오케스트레이터(order-service)에 결제 결과를 응답한다. 구현은 outbox 저장이라 호출한 트랜잭션과 함께 커밋된다 */
public interface SendPaymentReplyPort {

    void paymentApproved(UUID orderId, UUID paymentId);

    void paymentFailed(UUID orderId, String reason);

    /** 결제 취소를 접수했다 (환불이 필요하면 결제 서비스가 끝까지 한다) */
    void paymentCancelled(UUID orderId);
}
