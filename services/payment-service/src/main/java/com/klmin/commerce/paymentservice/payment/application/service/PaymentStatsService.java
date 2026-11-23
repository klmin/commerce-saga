package com.klmin.commerce.paymentservice.payment.application.service;

import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentStatsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentStatsResult;
import com.klmin.commerce.paymentservice.payment.application.port.out.LoadPaymentStatsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
class PaymentStatsService implements GetPaymentStatsUseCase {

    private final LoadPaymentStatsPort port;
    private final Clock clock;

    PaymentStatsService(LoadPaymentStatsPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentStatsResult getStats() {
        return port.loadPaymentStats(clock.instant());
    }
}
