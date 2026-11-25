package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayTimeoutException;
import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayUnavailableException;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.domain.Money;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakingPaymentGatewayTest {

    private final StubPg pg = new StubPg();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CircuitBreakingPaymentGateway gateway = new CircuitBreakingPaymentGateway((PaymentGatewayPort) pg,
            new PaymentCircuitBreakerProperties(true, 50, 4, 4, Duration.ofMillis(200), 1), meters);

    private PaymentGatewayPort.GatewayResult approve() {
        return gateway.approve(UUID.randomUUID(), 1L, Money.of(1_000));
    }

    @Test
    void opensAfterTimeouts_thenFailsFastWithoutCallingPg() {
        pg.timeout = true;
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(this::approve).isInstanceOf(PaymentGatewayTimeoutException.class);
        }
        assertThat(gateway.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(meters.get("payment_pg_circuit_state").gauge().value()).isEqualTo(1.0);

        int callsBefore = pg.calls.get();
        assertThatThrownBy(this::approve).isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(pg.calls.get()).isEqualTo(callsBefore);
    }

    @Test
    void declinesAreNotFailures() {
        pg.decline = true;
        for (int i = 0; i < 10; i++) {
            assertThat(approve().approved()).isFalse();
        }
        assertThat(gateway.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenTrialSucceeds_closesAgain() throws InterruptedException {
        pg.timeout = true;
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(this::approve).isInstanceOf(PaymentGatewayTimeoutException.class);
        }
        pg.timeout = false;
        Thread.sleep(300);

        assertThat(approve().approved()).isTrue();
        assertThat(gateway.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void inquireAndRefund_bypassCircuit() {
        pg.timeout = true;
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(this::approve).isInstanceOf(PaymentGatewayTimeoutException.class);
        }
        UUID orderId = UUID.randomUUID();
        assertThat(gateway.inquire(orderId)).isEmpty();
        gateway.refund(orderId);
        assertThat(pg.refunds.get()).isEqualTo(1);
    }

    @Test
    void disabled_passesThrough() {
        var off = new CircuitBreakingPaymentGateway((PaymentGatewayPort) pg,
                new PaymentCircuitBreakerProperties(false, 50, 4, 4, Duration.ofMinutes(1), 1), new SimpleMeterRegistry());
        pg.timeout = true;
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> off.approve(UUID.randomUUID(), 1L, Money.of(1)))
                    .isInstanceOf(PaymentGatewayTimeoutException.class);
        }
    }

    static final class StubPg implements PaymentGatewayPort {
        boolean timeout;
        boolean decline;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();

        @Override
        public GatewayResult approve(UUID orderId, long userId, Money amount) {
            calls.incrementAndGet();
            if (timeout) {
                throw new PaymentGatewayTimeoutException(orderId);
            }
            return decline ? GatewayResult.declined("no") : GatewayResult.approved("tx");
        }

        @Override
        public Optional<GatewayResult> inquire(UUID orderId) {
            return Optional.empty();
        }

        @Override
        public void refund(UUID orderId) {
            refunds.incrementAndGet();
        }
    }
}
