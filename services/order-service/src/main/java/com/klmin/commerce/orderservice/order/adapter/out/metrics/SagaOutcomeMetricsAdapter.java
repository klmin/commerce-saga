package com.klmin.commerce.orderservice.order.adapter.out.metrics;

import com.klmin.commerce.orderservice.order.application.port.out.SagaOutcomePort;
import com.klmin.commerce.orderservice.order.domain.SagaState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code commerce_saga_duration_seconds{result}} (Timer): 끝난 Saga 의 시작~끝 시간.
 * {@code _count} 가 결과별 완료·취소 건수, {@code _bucket} 으로 p95 를 본다.
 * 인스턴스마다 따로 세므로 대시보드는 {@code sum by (result)} 로 합친다. 재시작하면 0 부터 다시 센다(rate·increase 는 그대로 맞다).
 * <p>
 * 트랜잭션 안에서 불리면 커밋된 뒤에만 기록한다. 롤백되면 그 전이는 일어나지 않은 것이고, 재전달로 다시 처리될 때 한 번만 센다.
 */
@Component
class SagaOutcomeMetricsAdapter implements SagaOutcomePort {

    private final Map<SagaState, Timer> timers = new EnumMap<>(SagaState.class);

    SagaOutcomeMetricsAdapter(MeterRegistry registry) {
        // 처음부터 0 으로 보이도록 결과별로 미리 등록한다 (첫 완료 전에도 rate 가 0 으로 나온다)
        for (SagaState state : SagaState.values()) {
            if (!state.isFinished()) {
                continue;
            }
            timers.put(state, Timer.builder("commerce.saga.duration")
                    .tag("result", state.name())
                    .description("끝난 Saga 의 시작부터 끝까지 걸린 시간")
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(50))
                    .maximumExpectedValue(Duration.ofMinutes(10))
                    .register(registry));
        }
    }

    @Override
    public void sagaFinished(SagaState result, Duration elapsed) {
        Timer timer = timers.get(result);
        if (timer == null) {
            throw new IllegalArgumentException("not a finished saga state: " + result);
        }
        Duration nonNegative = elapsed.isNegative() ? Duration.ZERO : elapsed;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    timer.record(nonNegative);
                }
            });
        } else {
            timer.record(nonNegative);
        }
    }
}
