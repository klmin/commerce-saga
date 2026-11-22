package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.UUID;

/**
 * 외부 PG. orderId 를 멱등 키로 보내므로 같은 주문으로 다시 요청하면 PG 는 같은 결과를 돌려주고 두 번 승인하지 않는다
 * (실제 PG 의 주문번호 중복 방지와 같은 계약). 응답 직후 죽어 재처리될 때 이중 결제를 막는다.
 */
public interface PaymentGatewayPort {

    GatewayResult approve(UUID orderId, long userId, long amount);

    record GatewayResult(boolean approved, String transactionId, String reason) {

        public static GatewayResult approved(String transactionId) {
            return new GatewayResult(true, transactionId, null);
        }

        public static GatewayResult declined(String reason) {
            return new GatewayResult(false, null, reason);
        }
    }
}
