package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.port.out.SagaOutcomePort;
import com.klmin.commerce.orderservice.order.domain.SagaState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 끝난 Saga 결과 기록 대역 */
class InMemorySagaOutcomes implements SagaOutcomePort {

    record Outcome(SagaState result, Duration elapsed) {
    }

    final List<Outcome> recorded = new ArrayList<>();

    @Override
    public void sagaFinished(SagaState result, Duration elapsed) {
        recorded.add(new Outcome(result, elapsed));
    }
}
