package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.common.error.InvariantViolationException;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaOutcomePort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.SagaAction;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Saga 전이 결과({@link SagaAction})를 실행한다: 다음 커맨드 outbox 저장, 끝났으면 주문 상태 반영 + 결과 이벤트 + 결과 메트릭.
 * 응답 처리와 타임아웃 처리가 같이 쓴다. 호출한 쪽의 트랜잭션 안에서 Saga 변경과 함께 커밋된다.
 */
final class SagaActionExecutor {

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final SagaCommandPort commandPort;
    private final OrderEventPort eventPort;
    private final SagaOutcomePort outcomePort;

    SagaActionExecutor(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort, SagaCommandPort commandPort,
                       OrderEventPort eventPort, SagaOutcomePort outcomePort) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.commandPort = commandPort;
        this.eventPort = eventPort;
        this.outcomePort = outcomePort;
    }

    void execute(SagaAction action, OrderSaga saga, Instant now) {
        UUID orderId = saga.orderId();
        switch (action) {
            case SagaAction.ReserveStock _ -> commandPort.reserveStock(loadOrder(orderId));
            case SagaAction.UsePoint usePoint -> commandPort.usePoint(orderId, saga.userId(), usePoint.amount());
            case SagaAction.Pay pay -> commandPort.pay(orderId, saga.userId(), pay.amount());
            case SagaAction.RefundPoint _ -> commandPort.refundPoint(orderId);
            case SagaAction.ReleaseStock _ -> commandPort.releaseStock(orderId);
            case SagaAction.ReturnStock _ -> commandPort.returnStock(orderId);
            case SagaAction.CancelPayment cancel -> commandPort.cancelPayment(orderId, saga.userId(), cancel.amount());
            case SagaAction.Complete _ -> {
                Order order = loadOrder(orderId);
                if (order.complete(now)) {
                    saveOrderPort.update(order);
                    eventPort.orderCompleted(order);
                }
                recordFinished(saga, now);
            }
            case SagaAction.Cancel cancel -> {
                Order order = loadOrder(orderId);
                boolean changed = cancel.afterCompletion()
                        ? order.cancelAfterCompletion(cancel.reason(), now)
                        : order.cancel(cancel.reason(), now);
                if (changed) {
                    saveOrderPort.update(order);
                    eventPort.orderCancelled(order, cancel.afterCompletion());
                }
                recordFinished(saga, now);
            }
            case SagaAction.Ignore _ -> {
                // 호출한 쪽에서 이미 걸렀다
            }
        }
    }

    // Complete·Cancel 은 Saga 가 끝 상태로 바뀌는 전이에서 한 번만 나온다 (끝난 Saga 에 온 응답·타임아웃은 Ignore)
    private void recordFinished(OrderSaga saga, Instant now) {
        outcomePort.sagaFinished(saga.state(), Duration.between(saga.createdAt(), now));
    }

    private Order loadOrder(UUID orderId) {
        return loadOrderPort.findById(orderId)
                .orElseThrow(() -> new InvariantViolationException("saga exists but order is missing: " + orderId));
    }
}
