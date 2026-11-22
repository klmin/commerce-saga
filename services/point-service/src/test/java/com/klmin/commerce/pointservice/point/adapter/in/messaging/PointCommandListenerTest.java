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

    private final UsePointUseCase use = mock(UsePointUseCase.class);
    private final RefundPointUseCase refund = mock(RefundPointUseCase.class);
    private final PointCommandListener listener = new PointCommandListener(use, refund, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.POINT_COMMAND, 0, 0L, "k", value);
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void dispatchesByType() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(UsePoint.TYPE, "{\"orderId\":\"" + orderId + "\",\"userId\":1,\"amount\":300}"));
        listener.onMessage(record(RefundPoint.TYPE, "{\"orderId\":\"" + orderId + "\"}"));

        verify(use).use(new UsePointCommand(orderId, 1L, 300L));
        verify(refund).refund(orderId);
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
}
