package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.order.OrderCompleted;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ConfirmStockUseCase;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static com.klmin.commerce.inventoryservice.stock.adapter.in.messaging.InventoryCommandListenerTest.record;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventsListenerTest {

    private final ConfirmStockUseCase confirm = mock(ConfirmStockUseCase.class);
    private final OrderEventsListener listener = new OrderEventsListener(confirm, new JsonMapper());

    @Test
    void orderCompleted_confirmsStock() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(Topics.ORDER_EVENTS, OrderCompleted.TYPE,
                "{\"orderId\":\"" + orderId + "\",\"userId\":1,\"totalAmount\":100,\"pointAmount\":0,\"paidAmount\":100}"));

        verify(confirm).confirm(orderId);
    }

    @Test
    void otherOrUnknownEventTypes_areSkipped() {
        listener.onMessage(record(Topics.ORDER_EVENTS, OrderCancelled.TYPE, "{\"orderId\":\"" + UUID.randomUUID() + "\"}"));
        listener.onMessage(record(Topics.ORDER_EVENTS, "SomethingNew", "{}"));

        verifyNoInteractions(confirm);
    }

    @Test
    void missingTypeHeader_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(Topics.ORDER_EVENTS, null, "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(Topics.ORDER_EVENTS, "", "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(confirm);
    }

    @Test
    void invalidOrderCompleted_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(Topics.ORDER_EVENTS, OrderCompleted.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        // 필수 원시 값 누락은 Jackson 이 먼저 거른다. 둘 다 재시도 불가로 분류되어 DLT 로 간다
        assertThatThrownBy(() -> listener.onMessage(record(Topics.ORDER_EVENTS, OrderCompleted.TYPE, "{}")))
                .isInstanceOfAny(NonRetryableMessageException.class, JacksonException.class);
        assertThatThrownBy(() -> listener.onMessage(record(Topics.ORDER_EVENTS, OrderCompleted.TYPE,
                "{\"userId\":1,\"totalAmount\":1,\"pointAmount\":0,\"paidAmount\":1}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(confirm);
    }
}
