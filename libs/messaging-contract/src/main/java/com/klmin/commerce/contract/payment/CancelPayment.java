package com.klmin.commerce.contract.payment;

import java.util.UUID;

/**
 * 커맨드(order -> payment): 이 주문의 결제를 취소하라 (Saga 가 결제 결과를 끝내 못 받아 포기했을 때, ADR 0007).
 * 결제 서비스는 취소를 기록하고 바로 {@link PaymentCancelled} 로 응답한다. 이미 승인됐거나 나중에 승인이 확인되면
 * 환불까지 결제 서비스가 책임진다. 결제 기록이 없으면 취소 기록을 남겨 늦게 온 Pay 가 결제하지 않게 한다
 */
public record CancelPayment(UUID orderId, long userId, long amount) {

    public static final String TYPE = "CancelPayment";
}
