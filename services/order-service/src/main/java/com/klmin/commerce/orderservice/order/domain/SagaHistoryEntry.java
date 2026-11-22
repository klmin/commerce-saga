package com.klmin.commerce.orderservice.order.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Saga 진행 기록 한 줄 (관리자 Saga 상태 화면의 타임라인).
 *
 * @param event 단계를 바꾼 원인 (STARTED 또는 SagaEventType 이름)
 * @param state 그 결과 들어간 단계
 */
public record SagaHistoryEntry(String event, SagaState state, String reason, Instant occurredAt) {

    public SagaHistoryEntry {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
