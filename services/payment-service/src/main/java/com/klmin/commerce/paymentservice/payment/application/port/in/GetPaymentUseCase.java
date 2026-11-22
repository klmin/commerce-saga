package com.klmin.commerce.paymentservice.payment.application.port.in;

import com.klmin.commerce.paymentservice.payment.domain.Payment;

public interface GetPaymentUseCase {

    /** 없거나 다른 사용자의 결제면 PaymentNotFoundException (존재 여부를 드러내지 않는다) */
    Payment getPayment(GetPaymentQuery query);
}
