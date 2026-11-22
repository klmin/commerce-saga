package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleStockReplyUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InventoryReplyListenerTest {

    private final HandleStockReplyUseCase useCase = mock(HandleStockReplyUseCase.class);
    private final InventoryReplyListener listener = new InventoryReplyListener(useCase, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.INVENTORY_REPLY, 0, 0L, "k", value);
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void dispatchesByType() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(StockReserved.TYPE, "{\"orderId\":\"" + orderId + "\"}"));
        listener.onMessage(record(StockReservationFailed.TYPE,
                "{\"orderId\":\"" + orderId + "\",\"reason\":\"out\"}"));

        verify(useCase).stockReserved(orderId);
        verify(useCase).stockReservationFailed(orderId, "out");
    }

    @Test
    void emptyOrNullPayload_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(StockReserved.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(StockReserved.TYPE, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void missingOrderId_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(StockReservationFailed.TYPE, "{\"reason\":\"x\"}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void unknownOrMissingType_isIgnored() {
        listener.onMessage(record("Something", "{}"));
        listener.onMessage(record(null, "{}"));

        verifyNoInteractions(useCase);
    }
}
