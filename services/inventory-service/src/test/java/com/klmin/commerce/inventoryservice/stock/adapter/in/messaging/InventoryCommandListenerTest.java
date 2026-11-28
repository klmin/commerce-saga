package com.klmin.commerce.inventoryservice.stock.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.ReleaseStock;
import com.klmin.commerce.contract.inventory.ReserveStock;
import com.klmin.commerce.contract.inventory.ReturnStock;
import com.klmin.commerce.inventoryservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReleaseStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockCommand;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReserveStockUseCase;
import com.klmin.commerce.inventoryservice.stock.application.port.in.ReturnStockUseCase;
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

    static final UUID PRODUCT_ID = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
    static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ReserveStockUseCase reserve = mock(ReserveStockUseCase.class);
    private final ReleaseStockUseCase release = mock(ReleaseStockUseCase.class);
    private final ReturnStockUseCase returnStock = mock(ReturnStockUseCase.class);
    private final InventoryCommandListener listener =
            new InventoryCommandListener(reserve, release, returnStock, new JsonMapper());

    static ConsumerRecord<String, String> record(String topic, String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "k", value);
        record.headers().add(MessageHeaders.ID, MESSAGE_ID.toString().getBytes(StandardCharsets.UTF_8));
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static ConsumerRecord<String, String> record(String type, String value) {
        return record(Topics.INVENTORY_COMMAND, type, value);
    }

    @Test
    void reserveStock_callsUseCase() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(ReserveStock.TYPE,
                "{\"orderId\":\"" + orderId + "\",\"lines\":[{\"productId\":\"" + PRODUCT_ID + "\",\"quantity\":2}]}"));

        verify(reserve).reserve(MESSAGE_ID, new ReserveStockCommand(orderId, List.of(new ReserveStockCommand.Line(PRODUCT_ID, 2))));
    }

    @Test
    void releaseStock_callsUseCase() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(ReleaseStock.TYPE, "{\"orderId\":\"" + orderId + "\"}"));

        verify(release).release(MESSAGE_ID, orderId);
    }

    @Test
    void returnStock_callsUseCase() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(ReturnStock.TYPE, "{\"orderId\":\"" + orderId + "\"}"));

        verify(returnStock).returnStock(MESSAGE_ID, orderId);
    }

    @Test
    void missingOrInvalidIdHeader_isNonRetryable() {
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\"}";
        ConsumerRecord<String, String> missing = new ConsumerRecord<>(Topics.INVENTORY_COMMAND, 0, 0L, "k", body);
        missing.headers().add(MessageHeaders.TYPE, ReleaseStock.TYPE.getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> invalid = record(ReleaseStock.TYPE, body);
        invalid.headers().add(MessageHeaders.ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.onMessage(missing)).isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(invalid)).isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(reserve, release);
    }

    @Test
    void emptyPayloadOrMissingOrderId_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(ReserveStock.TYPE, "{\"lines\":[]}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(ReleaseStock.TYPE, "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(reserve, release);
    }

    @Test
    void unknownOrMissingType_isNonRetryable() {
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"lines\":[{\"productId\":\"" + PRODUCT_ID + "\",\"quantity\":1}]}";

        assertThatThrownBy(() -> listener.onMessage(record("Something", body)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(null, body)))
                .isInstanceOf(NonRetryableMessageException.class);

        ConsumerRecord<String, String> nullValueHeader = record(null, body);
        nullValueHeader.headers().add(MessageHeaders.TYPE, null);
        assertThatThrownBy(() -> listener.onMessage(nullValueHeader))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(reserve, release);
    }
}
