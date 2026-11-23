package com.klmin.commerce.paymentservice.payment.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * 외부 PG. orderId 를 멱등 키로 보내므로 같은 주문으로 다시 요청하면 PG 는 같은 결과를 돌려주고 두 번 승인하지 않는다
 * (실제 PG 의 주문번호 중복 방지와 같은 계약). 응답 직후 죽어 재처리될 때 이중 결제를 막는다.
 */
public interface PaymentGatewayPort {

    /**
     * 승인 요청.
     *
     * @throws com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException
     *         제한 시간 안에 응답이 없음. PG 는 계속 처리해 나중에 승인할 수 있다 → {@link #inquire} 로 확정
     */
    GatewayResult approve(UUID orderId, long userId, long amount);

    /** 거래 조회. PG 가 아직 처리 중이거나 받은 적 없으면 empty */
    Optional<GatewayResult> inquire(UUID orderId);

    /** 승인된 거래 환불(취소). 같은 주문으로 여러 번 불러도 한 번만 환불된다. 승인 거래가 없으면 아무것도 하지 않는다 */
    void refund(UUID orderId);

    record GatewayResult(boolean approved, String transactionId, String reason) {

        public static GatewayResult approved(String transactionId) {
            return new GatewayResult(true, transactionId, null);
        }

        public static GatewayResult declined(String reason) {
            return new GatewayResult(false, null, reason);
        }
    }
}
