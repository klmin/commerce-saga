package com.klmin.commerce.paymentservice.payment.application;

import java.util.UUID;

/**
 * PG 를 부르지 않았다 (서킷 브레이커가 열려 있음, PROGRESS 4-4절 ③). PG 에 요청이 가지 않았으므로 결과가 확정된 실패다:
 * "결과 모름"(타임아웃)과 달리 조회로 확정할 필요 없이 바로 결제 실패로 응답한다
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(UUID orderId) {
        super("PG unavailable (circuit open): orderId=" + orderId);
    }
}
