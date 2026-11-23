package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.in.GetSagaStatsUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult;
import com.klmin.commerce.orderservice.order.application.port.out.LoadSagaStatsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
class SagaStatsService implements GetSagaStatsUseCase {

    private final LoadSagaStatsPort port;
    private final Clock clock;

    SagaStatsService(LoadSagaStatsPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SagaStatsResult getStats() {
        return port.loadSagaStats(clock.instant());
    }
}
