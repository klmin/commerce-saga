package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.List;

public interface ListMyPaymentsUseCase {

    /** 요청한 사용자의 결제만 최신순으로 */
    List<PaymentSummaryResult> listMyPayments(ListMyPaymentsQuery query);
}
