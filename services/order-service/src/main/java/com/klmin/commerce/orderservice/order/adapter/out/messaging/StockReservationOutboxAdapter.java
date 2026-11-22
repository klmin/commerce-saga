package com.klmin.commerce.orderservice.order.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.orderservice.order.application.port.out.RequestStockReservationPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/** ReserveStock 커맨드를 outbox 에 저장한다. 메시지 키 = orderId */
@Component
class StockReservationOutboxAdapter implements RequestStockReservationPort {

    private final OutboxWriter outboxWriter;

    StockReservationOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void requestReservation(Order order) {
        ReserveStock command = new ReserveStock(order.id(), order.lines().stream()
                .map(l -> new ReserveStock.Line(l.productId(), l.quantity()))
                .toList());
        outboxWriter.append(Topics.INVENTORY_COMMAND, order.id().toString(), ReserveStock.TYPE, command);
    }
}
