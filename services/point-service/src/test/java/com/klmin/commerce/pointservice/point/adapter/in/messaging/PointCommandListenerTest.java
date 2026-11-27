package com.klmin.commerce.pointservice.point.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.point.RefundPoint;
import com.klmin.commerce.contract.point.UsePoint;
import com.klmin.commerce.pointservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.pointservice.point.application.port.in.RefundPointUseCase;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointCommand;
import com.klmin.commerce.pointservice.point.application.port.in.UsePointUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PointCommandListenerTest {

    static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID USER_ID = UUID.fromString("01900000-0000-7000-8001-000000000001");

    private final UsePointUseCase use = mock(UsePointUseCase.class);
    private final RefundPointUseCase refund = mock(RefundPointUseCase.class);
    private final PointCommandListener listener = new PointCommandListener(use, refund, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.POINT_COMMAND, 0, 0L, "k", value);
        record.headers().add(MessageHeaders.ID, MESSAGE_ID.toString().getBytes(StandardCharsets.UTF_8));
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void dispatchesByType() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(UsePoint.TYPE, "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + USER_ID + "\",\"amount\":300}"));
        listener.onMessage(record(RefundPoint.TYPE, "{\"orderId\":\"" + orderId + "\"}"));

        verify(use).use(MESSAGE_ID, new UsePointCommand(orderId, USER_ID, 300L));
        verify(refund).refund(MESSAGE_ID, orderId);
    }

    @Test
    void invalidMessages_areNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(UsePoint.TYPE, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(RefundPoint.TYPE, "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record("Something", "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(null, "{}")))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(use, refund);
    }

    @Test
    void missingOrInvalidIdHeader_isNonRetryable() {
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\"}";
        ConsumerRecord<String, String> missing = new ConsumerRecord<>(Topics.POINT_COMMAND, 0, 0L, "k", body);
        missing.headers().add(MessageHeaders.TYPE, RefundPoint.TYPE.getBytes(StandardCharsets.UTF_8));
        ConsumerRecord<String, String> invalid = record(RefundPoint.TYPE, body);
        invalid.headers().add(MessageHeaders.ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> listener.onMessage(missing)).isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(invalid)).isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(use, refund);
    }
}
