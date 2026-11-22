package com.klmin.commerce.paymentservice.payment.application.port.out;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;

/** 가짜 PG 의 장애 주입 설정 */
public interface PaymentGatewayFaultPort {

    Settings current();

    void change(Settings settings);
}
