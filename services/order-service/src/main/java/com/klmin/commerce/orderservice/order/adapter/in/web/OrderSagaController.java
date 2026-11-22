package com.klmin.commerce.orderservice.order.adapter.in.web;

import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 관리자용 Saga 진행 상태 (단계, 보상 여부, 실패 사유, 타임라인).
 * 관리자 전용이어야 하지만 인증·인가는 4단계(ADMIN 역할)에서 붙인다.
 */
@RestController
@RequestMapping("/admin/orders")
class OrderSagaController {

    private final GetOrderSagaUseCase getOrderSagaUseCase;

    OrderSagaController(GetOrderSagaUseCase getOrderSagaUseCase) {
        this.getOrderSagaUseCase = getOrderSagaUseCase;
    }

    @GetMapping("/{orderId}/saga")
    SagaResponse getSaga(@PathVariable UUID orderId) {
        OrderSaga saga = getOrderSagaUseCase.getSaga(orderId);
        return new SagaResponse(saga.orderId(), saga.state().name(), saga.state().isFinished(),
                saga.state().isCompensating(), saga.pointUsed(), saga.failureReason(),
                saga.history().stream()
                        .map(h -> new SagaResponse.Step(h.event(), h.state().name(), h.reason(), h.occurredAt()))
                        .toList());
    }

    record SagaResponse(UUID orderId, String state, boolean finished, boolean compensating, boolean pointUsed,
                        String failureReason, List<Step> history) {

        record Step(String event, String state, String reason, Instant occurredAt) {
        }
    }
}
