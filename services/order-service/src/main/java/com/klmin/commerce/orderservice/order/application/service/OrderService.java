package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.common.error.InvariantViolationException;
import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.SagaNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderSagaPort;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import com.klmin.commerce.orderservice.order.domain.OrderSaga;
import com.klmin.commerce.orderservice.order.domain.SagaAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 + Saga 오케스트레이터.
 * <p>
 * 한 트랜잭션에서 Saga 상태 변경 + 다음 커맨드(또는 결과 이벤트) outbox 저장 + 끝났으면 주문 상태 반영을 한다.
 * Order 와 OrderSaga 두 애그리거트를 함께 바꾸는 것은 의도한 타협이다 (같은 서비스·같은 DB, ADR 0003).
 */
@Service
class OrderService implements PlaceOrderUseCase, GetOrderUseCase, HandleSagaReplyUseCase, GetOrderSagaUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final OrderSagaPort sagaPort;
    private final SagaCommandPort commandPort;
    private final OrderEventPort eventPort;
    private final Clock clock;

    OrderService(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort, OrderSagaPort sagaPort,
                 SagaCommandPort commandPort, OrderEventPort eventPort, Clock clock) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.sagaPort = sagaPort;
        this.commandPort = commandPort;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    // 주문 + Saga 저장 + 재고 예약 커맨드(outbox) = 한 트랜잭션
    @Override
    @Transactional
    public Order placeOrder(PlaceOrderCommand command) {
        Instant now = clock.instant();
        List<OrderLine> lines = command.lines().stream()
                .map(l -> new OrderLine(l.productId(), l.quantity(), l.unitPrice()))
                .toList();
        Order order = Order.place(UUID.randomUUID(), command.userId(), lines, command.pointAmount(), now);
        saveOrderPort.insert(order);
        sagaPort.insert(OrderSaga.start(order, now));
        commandPort.reserveStock(order);
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(GetOrderQuery query) {
        return loadOrderPort.findById(query.orderId())
                .filter(order -> order.isOwnedBy(query.userId()))
                .orElseThrow(() -> new OrderNotFoundException(query.orderId()));
    }

    // Saga 전이 + 다음 커맨드/이벤트(outbox) + 주문 상태 = 한 트랜잭션
    @Override
    @Transactional
    public void handle(SagaReplyCommand reply) {
        Optional<OrderSaga> found = sagaPort.findByOrderIdForUpdate(reply.orderId());
        if (found.isEmpty()) {
            log.warn("Saga 없는 주문의 응답 무시: {} (orderId={})", reply.type(), reply.orderId());
            return;
        }
        OrderSaga saga = found.get();
        Instant now = clock.instant();
        SagaAction action = saga.handle(reply.type(), reply.reason(), now);
        if (action instanceof SagaAction.Ignore) {
            log.info("Saga 단계와 맞지 않는 응답 무시: {} (orderId={}, state={})",
                    reply.type(), reply.orderId(), saga.state());
            return;
        }
        sagaPort.update(saga);
        execute(action, saga, now);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSaga getSaga(GetOrderQuery query) {
        return sagaPort.findByOrderId(query.orderId())
                .filter(saga -> saga.userId() == query.userId())
                .orElseThrow(() -> new SagaNotFoundException(query.orderId()));
    }

    private void execute(SagaAction action, OrderSaga saga, Instant now) {
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
                // handle() 에서 이미 걸렀다
            }
        }
    }

    private Order loadOrder(UUID orderId) {
        return loadOrderPort.findById(orderId)
                .orElseThrow(() -> new InvariantViolationException("saga exists but order is missing: " + orderId));
    }
}
