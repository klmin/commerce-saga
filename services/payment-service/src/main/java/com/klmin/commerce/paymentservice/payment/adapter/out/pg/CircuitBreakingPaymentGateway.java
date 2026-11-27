package com.klmin.commerce.paymentservice.payment.adapter.out.pg;

import com.klmin.commerce.paymentservice.payment.application.PaymentGatewayUnavailableException;
import com.klmin.commerce.paymentservice.payment.application.port.out.PaymentGatewayPort;
import com.klmin.commerce.paymentservice.payment.domain.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * PG 승인 호출에 서킷 브레이커 (PROGRESS 4-4절 ③, Resilience4j). 타임아웃·오류가 이어지면 회로를 열고, 열려 있는 동안은
 * PG 를 부르지 않고 {@link PaymentGatewayUnavailableException} → 결제 서비스가 확정 실패(PaymentFailed)로 바로 응답해
 * Saga 가 곧바로 보상한다. PG 장애 동안 결제마다 클라이언트 타임아웃(3초)을 기다렸다 UNKNOWN 이 되는 것을 막는다.
 * <ul>
 *   <li>반열림의 시험 호출은 그대로 PG 로 간다. 타임아웃이면 지금처럼 UNKNOWN + 보정 (ADR 0007)</li>
 *   <li>거절(DECLINE)은 PG 가 정상 응답한 것이라 실패로 세지 않는다</li>
 *   <li>조회(inquire)·환불(refund)은 회로에 넣지 않는다: 보정 스케줄러가 회로와 무관하게 UNKNOWN 을 확정하고 늦은 승인을
 *       환불해야 돈이 맞는다. 보정은 자기 주기로 다시 시도한다</li>
 * </ul>
 * 상태는 인스턴스마다 따로다(인스턴스 여럿이면 각자 판단). 지표 {@code payment_pg_circuit_state}(0 닫힘, 1 열림, 2 반열림)
 */
@Primary
@Component
@EnableConfigurationProperties(PaymentCircuitBreakerProperties.class)
class CircuitBreakingPaymentGateway implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingPaymentGateway.class);

    private final PaymentGatewayPort delegate;
    private final CircuitBreaker circuitBreaker;
    private final boolean enabled;

    @Autowired
    CircuitBreakingPaymentGateway(FakePaymentGateway delegate, PaymentCircuitBreakerProperties properties,
                                  MeterRegistry meterRegistry) {
        this((PaymentGatewayPort) delegate, properties, meterRegistry);
    }

    CircuitBreakingPaymentGateway(PaymentGatewayPort delegate, PaymentCircuitBreakerProperties properties,
                                  MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.enabled = properties.enabled();
        this.circuitBreaker = CircuitBreaker.of("pg-approve", CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build());
        circuitBreaker.getEventPublisher().onStateTransition(e ->
                log.warn("PG 승인 서킷 브레이커 상태 변경: {}", e.getStateTransition()));
        Gauge.builder("payment_pg_circuit_state", circuitBreaker, cb -> switch (cb.getState()) {
                    case CLOSED, DISABLED, METRICS_ONLY -> 0;
                    case OPEN, FORCED_OPEN -> 1;
                    case HALF_OPEN -> 2;
                })
                .description("PG 승인 서킷 브레이커 (0 닫힘, 1 열림, 2 반열림)")
                .register(meterRegistry);
    }

    @Override
    public GatewayResult approve(UUID orderId, UUID userId, Money amount) {
        if (!enabled) {
            return delegate.approve(orderId, userId, amount);
        }
        try {
            return circuitBreaker.executeSupplier(() -> delegate.approve(orderId, userId, amount));
        } catch (CallNotPermittedException e) {
            throw new PaymentGatewayUnavailableException(orderId);
        }
    }

    @Override
    public Optional<GatewayResult> inquire(UUID orderId) {
        return delegate.inquire(orderId);
    }

    @Override
    public void refund(UUID orderId) {
        delegate.refund(orderId);
    }

    CircuitBreaker.State state() {
        return circuitBreaker.getState();
    }
}
