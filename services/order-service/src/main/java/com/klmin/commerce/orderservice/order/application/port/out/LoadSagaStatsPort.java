package com.klmin.commerce.orderservice.order.application.port.out;

import com.klmin.commerce.orderservice.order.application.port.in.SagaStatsResult;

import java.time.Instant;

/** Saga 통계 조회 (애그리거트를 거치지 않는 읽기 전용 집계) */
public interface LoadSagaStatsPort {

    SagaStatsResult loadSagaStats(Instant now);
}
