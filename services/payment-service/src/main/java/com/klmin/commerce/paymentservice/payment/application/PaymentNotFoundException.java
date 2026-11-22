package com.klmin.commerce.paymentservice.payment.application;

import com.klmin.commerce.paymentservice.common.error.NotFoundException;

import java.util.UUID;

public class PaymentNotFoundException extends NotFoundException {

    public PaymentNotFoundException(UUID orderId) {
        super("payment not found: orderId=" + orderId);
    }
}
