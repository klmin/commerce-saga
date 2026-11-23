package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.SagaState;

import java.time.Duration;
import java.util.Map;

/**
 * 진행 중 Saga 통계 (메트릭용).
 *
 * @param active          끝나지 않은 단계별 Saga 수와 그중 가장 오래된 것의 나이(시작부터). 없는 단계는 빠진다
 * @param timeoutResends  지금까지 단계 타임아웃으로 재전송한 횟수 (Saga 기록 TIMEOUT_RESEND)
 * @param timeoutGiveUps  지금까지 재전송을 포기하고 보상으로 넘어간 횟수 (Saga 기록 TIMEOUT_GIVE_UP)
 */
public record SagaStatsResult(Map<SagaState, StateResult> active, long timeoutResends, long timeoutGiveUps) {

    public record StateResult(long count, Duration oldestAge) {
    }
}
