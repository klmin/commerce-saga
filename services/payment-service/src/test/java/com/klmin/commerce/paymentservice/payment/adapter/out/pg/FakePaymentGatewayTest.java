package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Mode;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentFaultUseCase.Settings;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort.GatewayResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FakePaymentGatewayTest {

    @Test
    void sameOrder_returnsFirstResult_evenAfterModeChange() {
        FakePaymentGateway gateway = new FakePaymentGateway(Mode.APPROVE, 0);
        UUID orderId = UUID.randomUUID();

        GatewayResult first = gateway.approve(orderId, 1L, 100L);
        gateway.change(new Settings(Mode.DECLINE, 0));
        GatewayResult second = gateway.approve(orderId, 1L, 100L);

        assertThat(first.approved()).isTrue();
        assertThat(second).isEqualTo(first);
        assertThat(gateway.approve(UUID.randomUUID(), 1L, 100L).approved()).isFalse();
    }

    @Test
    void delayMode_waitsThenApproves() {
        FakePaymentGateway gateway = new FakePaymentGateway(Mode.DELAY, 50);

        long start = System.nanoTime();
        GatewayResult result = gateway.approve(UUID.randomUUID(), 1L, 100L);

        assertThat(result.approved()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(45);
    }
}
