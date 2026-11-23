package com.klmin.commerce.orderservice.order.application.port.in;

/** 진행 중 Saga 통계. 메트릭(/actuator/prometheus)이 주기적으로 읽는다 (ADR 0013) */
public interface GetSagaStatsUseCase {

    SagaStatsResult getStats();
}
