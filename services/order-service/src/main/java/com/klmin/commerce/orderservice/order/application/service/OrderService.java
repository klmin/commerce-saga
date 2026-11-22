package com.klmin.commerce.orderservice.order.application.service;

import com.klmin.commerce.orderservice.order.application.OrderNotFoundException;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderQuery;
import com.klmin.commerce.orderservice.order.application.port.in.GetOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.HandleStockReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderCommand;
import com.klmin.commerce.orderservice.order.application.port.in.PlaceOrderUseCase;
import com.klmin.commerce.orderservice.order.application.port.out.LoadOrderPort;
import com.klmin.commerce.orderservice.order.application.port.out.RequestStockReservationPort;
import com.klmin.commerce.orderservice.order.application.port.out.SaveOrderPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.OrderLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
class OrderService implements PlaceOrderUseCase, GetOrderUseCase, HandleStockReplyUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final LoadOrderPort loadOrderPort;
    private final SaveOrderPort saveOrderPort;
    private final RequestStockReservationPort requestStockReservationPort;
    private final Clock clock;

    OrderService(LoadOrderPort loadOrderPort, SaveOrderPort saveOrderPort,
                 RequestStockReservationPort requestStockReservationPort, Clock clock) {
        this.loadOrderPort = loadOrderPort;
        this.saveOrderPort = saveOrderPort;
        this.requestStockReservationPort = requestStockReservationPort;
        this.clock = clock;
    }

    // 주문 저장 + 재고 예약 커맨드(outbox) 저장 = 한 트랜잭션
    @Override
    @Transactional
    public Order placeOrder(PlaceOrderCommand command) {
        List<OrderLine> lines = command.lines().stream()
                .map(l -> new OrderLine(l.productId(), l.quantity(), l.unitPrice()))
                .toList();
        Order order = Order.place(UUID.randomUUID(), command.userId(), lines, clock.instant());
        saveOrderPort.insert(order);
        requestStockReservationPort.requestReservation(order);
        return order;
    }

    @Override
    @Transactional(readOnly = true)
    public Order getOrder(GetOrderQuery query) {
        return loadOrderPort.findById(query.orderId())
                .filter(order -> order.isOwnedBy(query.userId()))
                .orElseThrow(() -> new OrderNotFoundException(query.orderId()));
    }

    @Override
    @Transactional
    public void stockReserved(UUID orderId) {
        loadOrderPort.findById(orderId).ifPresentOrElse(order -> {
            if (order.confirm(clock.instant())) {
                saveOrderPort.update(order);
            } else {
                log.info("재고 예약 성공 응답 무시: 이미 {} 상태 (orderId={})", order.status(), orderId);
            }
        }, () -> log.warn("재고 예약 성공 응답의 주문이 없음 (orderId={})", orderId));
    }

    @Override
    @Transactional
    public void stockReservationFailed(UUID orderId, String reason) {
        loadOrderPort.findById(orderId).ifPresentOrElse(order -> {
            if (order.reject(reason, clock.instant())) {
                saveOrderPort.update(order);
            } else {
                log.info("재고 예약 실패 응답 무시: 이미 {} 상태 (orderId={})", order.status(), orderId);
            }
        }, () -> log.warn("재고 예약 실패 응답의 주문이 없음 (orderId={})", orderId));
    }
}
