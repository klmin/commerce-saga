package com.klmin.commerce.orderservice.order.adapter.in.metrics;

import com.klmin.commerce.orderservice.order.application.port.in.GetSagaStatsUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult;
import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult.StateResult;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import io.micrometer.core.instrument.FunctionCounter;
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
 * Saga 진행 상황 메트릭 (ADR 0013). Prometheus 경보 규칙(infra/prometheus/rules)이 이 값을 본다.
 * <ul>
 *   <li>{@code commerce_saga_active{state}}: 끝나지 않은 단계별 Saga 수</li>
 *   <li>{@code commerce_saga_oldest_active_age_seconds{state}}: 그 단계에 있는 가장 오래된 Saga 가 시작한 지 얼마나 됐나.
 *       정상 주문은 몇 초 안에 끝나므로 길면 멈춘 것 (타임아웃 재전송 중, 보상이 계속 실패 등)</li>
 *   <li>{@code commerce_saga_timeout_resends_total}, {@code commerce_saga_timeout_give_ups_total}: 단계 타임아웃 재전송·포기 누적</li>
 * </ul>
 * 스크레이프 때 조회하되 2초 동안 재사용한다 (게이지마다 쿼리하지 않게).
 */
@Component
class SagaMetrics implements MeterBinder {

    private static final Duration CACHE_FOR = Duration.ofSeconds(2);

    private final GetSagaStatsUseCase useCase;
    private final Clock clock;
    private SagaStatsResult cached;
    private Instant cachedAt;

    SagaMetrics(GetSagaStatsUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (SagaState state : SagaState.values()) {
            if (state.isFinished()) {
                continue;
            }
            String phase = state.isCompensating() ? "compensating" : "forward";
            Gauge.builder("commerce.saga.active", this, m -> m.state(state).count())
                    .tag("state", state.name())
                    .tag("phase", phase)
                    .description("끝나지 않은 Saga 수")
                    .register(registry);
            TimeGauge.builder("commerce.saga.oldest.active.age", this, TimeUnit.SECONDS,
                            m -> m.state(state).oldestAge().toSeconds())
                    .tag("state", state.name())
                    .tag("phase", phase)
                    .description("이 단계에 있는 가장 오래된 Saga 가 시작한 지 지난 시간")
                    .register(registry);
        }
        FunctionCounter.builder("commerce.saga.timeout.resends", this, m -> m.stats().timeoutResends())
                .description("단계 타임아웃으로 커맨드를 다시 보낸 횟수")
                .register(registry);
        FunctionCounter.builder("commerce.saga.timeout.give.ups", this, m -> m.stats().timeoutGiveUps())
                .description("재전송을 포기하고 보상으로 넘어간 횟수")
                .register(registry);
    }

    private StateResult state(SagaState state) {
        return stats().active().getOrDefault(state, new StateResult(0, Duration.ZERO));
    }

    private synchronized SagaStatsResult stats() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(cachedAt.plus(CACHE_FOR))) {
            cached = useCase.getStats();
            cachedAt = now;
        }
        return cached;
    }
}
