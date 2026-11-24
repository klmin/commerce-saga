package com.klmin.commerce.orderservice.order.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.orderservice.order.application.port.out.OrderEventPort;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

/** 주문 결과 이벤트를 order.events outbox 에 저장한다. 메시지 키 = orderId. 누가 받는지는 모른다 */
@Component
class OrderEventOutboxAdapter implements OrderEventPort {

    private final OutboxWriter outboxWriter;

    OrderEventOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void orderCompleted(Order order) {
        outboxWriter.append(Topics.ORDER_EVENTS, order.id().toString(), OrderCompleted.TYPE,
                new OrderCompleted(order.id(), order.userId().value(), order.totalAmount().value(),
                        order.pointAmount().value(), order.paidAmount().value()));
    }

    @Override
    public void orderCancelled(Order order) {
        outboxWriter.append(Topics.ORDER_EVENTS, order.id().toString(), OrderCancelled.TYPE,
                new OrderCancelled(order.id(), order.userId().value(), order.cancelReason()));
    }
}
