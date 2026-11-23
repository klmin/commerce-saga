package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.common.error.InvariantViolationException;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.SagaAction;

import java.time.Instant;
import java.util.UUID;

/**
 * Saga 전이 결과({@link SagaAction})를 실행한다: 다음 커맨드 outbox 저장, 끝났으면 주문 상태 반영 + 결과 이벤트.
 * 응답 처리와 타임아웃 처리가 같이 쓴다. 호출한 쪽의 트랜잭션 안에서 Saga 변경과 함께 커밋된다.
 */
final class SagaActionExecutor {

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SagaCommandPort commandPort;
    private final OrderEventPort eventPort;

    SagaActionExecutor(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort, SagaCommandPort commandPort,
                       OrderEventPort eventPort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.commandPort = commandPort;
        this.eventPort = eventPort;
    }

    void execute(SagaAction action, OrderSaga saga, Instant now) {
        UUID orderId = saga.orderId();
        switch (action) {
            case SagaAction.ReserveStock ignored -> commandPort.reserveStock(loadOrder(orderId));
            case SagaAction.UsePoint usePoint -> commandPort.usePoint(orderId, saga.userId(), usePoint.amount());
            case SagaAction.Pay pay -> commandPort.pay(orderId, saga.userId(), pay.amount());
            case SagaAction.RefundPoint ignored -> commandPort.refundPoint(orderId);
            case SagaAction.ReleaseStock ignored -> commandPort.releaseStock(orderId);
            case SagaAction.Complete ignored -> {
                Order order = loadOrder(orderId);
                if (order.complete(now)) {
                    saveOrderPort.update(order);
                    eventPort.orderCompleted(order);
                }
            }
            case SagaAction.Cancel cancel -> {
                Order order = loadOrder(orderId);
                if (order.cancel(cancel.reason(), now)) {
                    saveOrderPort.update(order);
                    eventPort.orderCancelled(order);
                }
            }
            case SagaAction.Ignore ignored -> {
                // 호출한 쪽에서 이미 걸렀다
            }
        }
    }

    private Order loadOrder(UUID orderId) {
        return loadOrderPort.findById(orderId)
                .orElseThrow(() -> new InvariantViolationException("saga exists but order is missing: " + orderId));
    }
}
