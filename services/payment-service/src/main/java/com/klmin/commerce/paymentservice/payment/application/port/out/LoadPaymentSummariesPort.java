package com.klmin.commerce.paymentservice.payment.application.port.out;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentSummaryResult;

import java.util.List;
import java.util.UUID;

public interface LoadPaymentSummariesPort {

    /** 사용자의 결제를 최신순(id 내림차순)으로. before 가 있으면 그보다 오래된 것만 */
    List<PaymentSummaryResult> findByUser(long userId, UUID before, int size);
}
