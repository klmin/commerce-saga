package com.klmin.commerce.inventoryservice.stock.adapter.out.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReturned;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.inventoryservice.stock.application.port.out.SendStockReplyPort;
import com.klmin.commerce.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 재고 예약 응답을 outbox 에 저장한다. 메시지 키 = orderId */
@Component
class StockReplyOutboxAdapter implements SendStockReplyPort {

    private final OutboxWriter outboxWriter;

    StockReplyOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void stockReserved(UUID orderId) {
        outboxWriter.append(Topics.INVENTORY_REPLY, orderId.toString(), StockReserved.TYPE,
                new StockReserved(orderId));
    }

    @Override
    public void stockReservationFailed(UUID orderId, String reason) {
        outboxWriter.append(Topics.INVENTORY_REPLY, orderId.toString(), StockReservationFailed.TYPE,
                new StockReservationFailed(orderId, reason));
    }

    @Override
    public void stockReleased(UUID orderId) {
        outboxWriter.append(Topics.INVENTORY_REPLY, orderId.toString(), StockReleased.TYPE,
                new StockReleased(orderId));
    }

    @Override
    public void stockReturned(UUID orderId) {
        outboxWriter.append(Topics.INVENTORY_REPLY, orderId.toString(), StockReturned.TYPE,
                new StockReturned(orderId));
    }
}
