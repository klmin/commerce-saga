package com.klmin.commerce.notificationservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCompleted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderEventsListenerTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final OrderEventsListener listener = new OrderEventsListener(repository, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.ORDER_EVENTS, 0, 0L, "k", value);
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void orderCompleted_createsNotification() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(OrderCompleted.TYPE, "{\"orderId\":\"" + orderId
                + "\",\"userId\":7,\"totalAmount\":2000,\"pointAmount\":500,\"paidAmount\":1500}"));

        verify(repository).insertIfAbsent(eq(orderId), eq(7L), eq(OrderCompleted.TYPE), anyString());
    }

    @Test
    void unknownType_isSkipped() {
        listener.onMessage(record("SomethingNew", "{}"));

        verifyNoInteractions(repository);
    }

    @Test
    void missingOrEmptyTypeHeader_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(null, "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record("", "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        ConsumerRecord<String, String> nullValue = record(null, "{}");
        nullValue.headers().add(MessageHeaders.TYPE, null);
        assertThatThrownBy(() -> listener.onMessage(nullValue))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void invalidPayload_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(OrderCompleted.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(OrderCompleted.TYPE,
                "{\"userId\":7,\"totalAmount\":1,\"pointAmount\":0,\"paidAmount\":1}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verify(repository, org.mockito.Mockito.never()).insertIfAbsent(org.mockito.ArgumentMatchers.any(), anyLong(),
                anyString(), anyString());
    }
}
