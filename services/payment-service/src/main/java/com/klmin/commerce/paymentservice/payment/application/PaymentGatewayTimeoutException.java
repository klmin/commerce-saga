package com.klmin.commerce.paymentservice.payment.application;

import java.util.UUID;

/**
 * PG 가 제한 시간 안에 응답하지 않았다. 승인됐는지 알 수 없으므로 실패로 응답하지 않고 결제를 UNKNOWN 으로 기록한 뒤
 * PG 조회로 확정한다 (ADR 0007)
 */
public class PaymentGatewayTimeoutException extends RuntimeException {

    public PaymentGatewayTimeoutException(UUID orderId) {
        super("PG response timeout: orderId=" + orderId);
    }
}
