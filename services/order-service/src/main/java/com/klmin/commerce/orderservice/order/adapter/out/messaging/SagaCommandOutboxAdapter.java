package com.klmin.commerce.orderservice.order.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.payment.CancelPayment;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.orderservice.order.application.port.out.SagaCommandPort;
import com.klmin.commerce.orderservice.order.domain.Money;
import com.klmin.commerce.orderservice.order.domain.Order;
import com.klmin.commerce.orderservice.order.domain.Points;
import com.klmin.commerce.orderservice.order.domain.UserId;
import com.klmin.commerce.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Saga 커맨드를 각 서비스의 command 토픽 outbox 에 저장한다. 메시지 키 = orderId */
@Component
class SagaCommandOutboxAdapter implements SagaCommandPort {

    private final OutboxWriter outboxWriter;

    SagaCommandOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void reserveStock(Order order) {
        ReserveStock command = new ReserveStock(order.id(), order.lines().stream()
                .map(l -> new ReserveStock.Line(l.productId().value(), l.quantity().value()))
                .toList());
        outboxWriter.append(Topics.INVENTORY_COMMAND, order.id().toString(), ReserveStock.TYPE, command);
    }

    @Override
    public void releaseStock(UUID orderId) {
        outboxWriter.append(Topics.INVENTORY_COMMAND, orderId.toString(), ReleaseStock.TYPE,
                new ReleaseStock(orderId));
    }

    @Override
    public void usePoint(UUID orderId, UserId userId, Points amount) {
        outboxWriter.append(Topics.POINT_COMMAND, orderId.toString(), UsePoint.TYPE,
                new UsePoint(orderId, userId.value(), amount.value()));
    }

    @Override
    public void refundPoint(UUID orderId) {
        outboxWriter.append(Topics.POINT_COMMAND, orderId.toString(), RefundPoint.TYPE, new RefundPoint(orderId));
    }

    @Override
    public void pay(UUID orderId, UserId userId, Money amount) {
        outboxWriter.append(Topics.PAYMENT_COMMAND, orderId.toString(), Pay.TYPE, new Pay(orderId, userId.value(), amount.value()));
    }

    @Override
    public void cancelPayment(UUID orderId, UserId userId, Money amount) {
        outboxWriter.append(Topics.PAYMENT_COMMAND, orderId.toString(), CancelPayment.TYPE,
                new CancelPayment(orderId, userId.value(), amount.value()));
    }
}
