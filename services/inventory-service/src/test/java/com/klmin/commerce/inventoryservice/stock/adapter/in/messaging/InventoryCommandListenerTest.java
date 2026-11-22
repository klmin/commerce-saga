package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InventoryCommandListenerTest {

    private final ReserveStockUseCase useCase = mock(ReserveStockUseCase.class);
    private final InventoryCommandListener listener = new InventoryCommandListener(useCase, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.INVENTORY_COMMAND, 0, 0L, "k", value);
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void reserveStock_callsUseCase() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(ReserveStock.TYPE,
                "{\"orderId\":\"" + orderId + "\",\"lines\":[{\"productId\":1,\"quantity\":2}]}"));

        verify(useCase).reserve(new ReserveStockCommand(orderId, List.of(new ReserveStockCommand.Line(1L, 2))));
    }

    @Test
    void emptyPayloadOrMissingOrderId_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, "{\"lines\":[]}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void unknownOrMissingType_isNonRetryable() {
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"productId\":1,\"quantity\":1}]}";

        assertThatThrownBy(() -> listener.onMessage(record("Something", body)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(null, body)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(useCase);
    }
}
