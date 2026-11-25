package com.klmin.commerce.orderservice.order.adapter.out.metrics;

import com.klmin.commerce.orderservice.order.domain.SagaState;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SagaOutcomeMetricsAdapterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SagaOutcomeMetricsAdapter adapter = new SagaOutcomeMetricsAdapter(registry);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Timer timer(SagaState result) {
        return registry.get("commerce.saga.duration").tag("result", result.name()).timer();
    }

    @Test
    void bothResultsAreRegisteredAtZero() {
        assertThat(timer(SagaState.COMPLETED).count()).isZero();
        assertThat(timer(SagaState.CANCELLED).count()).isZero();
    }

    @Test
    void withoutTransaction_recordsImmediately() {
        adapter.sagaFinished(SagaState.COMPLETED, Duration.ofMillis(1_500));

        assertThat(timer(SagaState.COMPLETED).count()).isEqualTo(1);
        assertThat(timer(SagaState.COMPLETED).totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_500);
    }

    @Test
    void insideTransaction_recordsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.sagaFinished(SagaState.CANCELLED, Duration.ofSeconds(3));
        assertThat(timer(SagaState.CANCELLED).count()).isZero();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(timer(SagaState.CANCELLED).count()).isEqualTo(1);
    }

    @Test
    void rolledBack_isNotRecorded() {
        TransactionSynchronizationManager.initSynchronization();

        adapter.sagaFinished(SagaState.COMPLETED, Duration.ofSeconds(1));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(timer(SagaState.COMPLETED).count()).isZero();
    }

    @Test
    void unfinishedState_isRejected() {
        assertThatThrownBy(() -> adapter.sagaFinished(SagaState.PAYING, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
