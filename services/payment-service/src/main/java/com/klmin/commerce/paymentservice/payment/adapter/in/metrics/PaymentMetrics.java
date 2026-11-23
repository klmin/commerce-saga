package com.klmin.commerce.paymentservice.payment.adapter.in.metrics;

import com.klmin.commerce.paymentservice.payment.application.port.in.GetPaymentStatsUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PaymentStatsResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 결제 보정 메트릭 (ADR 0007·0013). 결과를 모르는 결제가 오래 남거나 환불이 밀리면 경보한다.
 * <ul>
 *   <li>{@code commerce_payment_unknown}, {@code commerce_payment_oldest_unknown_age_seconds}</li>
 *   <li>{@code commerce_payment_refund_pending}, {@code commerce_payment_oldest_refund_pending_age_seconds}</li>
 * </ul>
 * 스크레이프 때 조회하되 2초 동안 재사용한다.
 */
@Component
class PaymentMetrics implements MeterBinder {

    private static final Duration CACHE_FOR = Duration.ofSeconds(2);

    private final GetPaymentStatsUseCase useCase;
    private final Clock clock;
    private PaymentStatsResult cached;
    private Instant cachedAt;

    PaymentMetrics(GetPaymentStatsUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("commerce.payment.unknown", this, m -> m.stats().unknown())
                .description("PG 결과를 모르는 결제 수 (조회로 확정 전)")
                .register(registry);
        TimeGauge.builder("commerce.payment.oldest.unknown.age", this, TimeUnit.SECONDS,
                        m -> m.stats().oldestUnknownAge().toSeconds())
                .description("가장 오래된 UNKNOWN 결제가 그 상태로 지난 시간")
                .register(registry);
        Gauge.builder("commerce.payment.refund.pending", this, m -> m.stats().refundPending())
                .description("취소 요청 + 승인 상태라 PG 환불을 기다리는 결제 수")
                .register(registry);
        TimeGauge.builder("commerce.payment.oldest.refund.pending.age", this, TimeUnit.SECONDS,
                        m -> m.stats().oldestRefundPendingAge().toSeconds())
                .description("가장 오래된 환불 대기 결제가 그 상태로 지난 시간")
                .register(registry);
    }

    private synchronized PaymentStatsResult stats() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(cachedAt.plus(CACHE_FOR))) {
            cached = useCase.getStats();
            cachedAt = now;
        }
        return cached;
    }
}
