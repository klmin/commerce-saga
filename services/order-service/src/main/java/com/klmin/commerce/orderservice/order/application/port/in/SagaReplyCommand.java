package com.klmin.commerce.orderservice.order.application.port.in;

import com.klmin.commerce.orderservice.order.domain.SagaEventType;

import java.util.Objects;
import java.util.UUID;

/** 참여 서비스 응답 (reason 은 실패 응답일 때만) */
public record SagaReplyCommand(UUID orderId, SagaEventType type, String reason) {

    public SagaReplyCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(type, "type");
    }

    public static SagaReplyCommand of(UUID orderId, SagaEventType type) {
        return new SagaReplyCommand(orderId, type, null);
    }
}
