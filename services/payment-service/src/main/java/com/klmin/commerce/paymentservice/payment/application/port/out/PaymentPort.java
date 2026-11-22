package com.klmin.commerce.paymentservice.payment.application.port.out;

import com.klmin.commerce.paymentservice.payment.domain.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentPort {

    Optional<Payment> findByOrderId(UUID orderId);

    /** 같은 orderId 가 이미 있으면 예외 (동시에 처리된 중복 커맨드 중 하나만 커밋된다) */
    void insert(Payment payment);
}
