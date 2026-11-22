package com.klmin.commerce.orderservice.order.adapter.out.persistence;

import com.klmin.commerce.orderservice.order.domain.SagaState;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;

@Embeddable
class SagaHistoryEmbeddable {

    @Column(nullable = false, length = 50)
    private String event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaState state;

    @Column(length = 200)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SagaHistoryEmbeddable() {
    }

    SagaHistoryEmbeddable(String event, SagaState state, String reason, Instant occurredAt) {
        this.event = event;
        this.state = state;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    String getEvent() {
        return event;
    }

    SagaState getState() {
        return state;
    }

    String getReason() {
        return reason;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
