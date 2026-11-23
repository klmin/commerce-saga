package com.klmin.commerce.orderservice.order.application.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.klmin.commerce.orderservice.order.application.IdempotencyKeyReusedException;
import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.SagaNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderSagaUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderResult;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderIdempotencyPort;
import com.klmin.commerce.orderservice.order.application.port.out.OrderSagaPort;
import com.klmin.commerce.orderservice.order.application.port.out.ProcessedMessagePort;
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
    private final SagaActionExecutor executor;
    private final ProcessedMessagePort processedMessagePort;
    private final OrderIdempotencyPort idempotencyPort;
    private final Clock clock;

    OrderService(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort, OrderSagaPort sagaPort,
                 SagaCommandPort commandPort, OrderEventPort eventPort, ProcessedMessagePort processedMessagePort,
                 OrderIdempotencyPort idempotencyPort,
                 Clock clock) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.sagaPort = sagaPort;
        this.commandPort = commandPort;
        this.executor = new SagaActionExecutor(loadOrderPort, saveOrderPort, commandPort, eventPort);
        this.processedMessagePort = processedMessagePort;
        this.idempotencyPort = idempotencyPort;
        this.clock = clock;
    }

    // (키 선점) + 주문 + Saga 저장 + 재고 예약 커맨드(outbox) = 한 트랜잭션
    @Override
    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderCommand command) {
        Instant now = clock.instant();
        List<OrderLine> lines = command.lines().stream()
                .map(l -> new OrderLine(l.productId(), l.quantity(), l.unitPrice()))
                .toList();
        // 도메인 검증을 먼저 해서 잘못된 요청은 키를 선점하지 않는다 (어차피 롤백되지만 기다림을 줄인다)
        Order order = Order.place(UuidCreator.getTimeOrderedEpoch(), command.userId(), lines, command.pointAmount(), now);
        if (command.idempotencyKey() != null) {
            String hash = OrderRequestHash.of(command);
            Optional<OrderIdempotencyPort.KeyRecord> existing =
                    idempotencyPort.claim(command.userId(), command.idempotencyKey(), hash, order.id(), now);
            if (existing.isPresent()) {
                return replay(command, existing.get(), hash);
            }
        }
        saveOrderPort.insert(order);
        sagaPort.insert(OrderSaga.start(order, now));
        commandPort.reserveStock(order);
        return new PlaceOrderResult(order, true);
    }

    // 같은 키의 재요청: 처음 주문을 그대로 돌려준다. 커맨드는 다시 보내지 않는다 (Saga 는 처음 주문 것 하나)
    private PlaceOrderResult replay(PlaceOrderCommand command, OrderIdempotencyPort.KeyRecord existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
        Order first = loadOrderPort.findById(existing.orderId())
                .filter(o -> o.isOwnedBy(command.userId()))
                .orElseThrow(() -> new IllegalStateException("idempotency key without order: " + existing.orderId()));
        log.info("같은 Idempotency-Key 재요청, 처음 주문 반환 (userId={}, orderId={})", command.userId(), first.id());
        return new PlaceOrderResult(first, false);
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
    public void handle(UUID messageId, SagaReplyCommand reply) {
        // 같은 메시지의 재전달은 건너뛴다. 내용이 같은 다른 메시지(중복·늦은 응답)는 아래 상태 머신이 무시한다
        if (!processedMessagePort.markProcessed(messageId)) {
            log.info("이미 처리한 응답 건너뜀: {} (messageId={}, orderId={})", reply.type(), messageId, reply.orderId());
            return;
        }
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
        executor.execute(action, saga, now);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSaga getSaga(GetOrderQuery query) {
        return sagaPort.findByOrderId(query.orderId())
                .filter(saga -> saga.userId() == query.userId())
                .orElseThrow(() -> new SagaNotFoundException(query.orderId()));
    }
}
