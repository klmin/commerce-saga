package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.Objects;
import java.util.UUID;

/** 주문 쪽 결제 취소 (Saga 가 결제 결과를 끝내 못 받아 포기함). amount 는 결제 기록이 없을 때 취소 기록용 */
public record CancelPaymentCommand(UUID orderId, UUID userId, long amount) {

    public CancelPaymentCommand {
        Objects.requireNonNull(orderId, "orderId");
    }
}
