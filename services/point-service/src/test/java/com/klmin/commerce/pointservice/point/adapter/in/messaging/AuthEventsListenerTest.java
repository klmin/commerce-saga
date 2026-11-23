package com.klmin.commerce.pointservice.point.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.auth.UserRegistered;
import com.klmin.commerce.pointservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountCommand;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AuthEventsListenerTest {

    static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final OpenPointAccountUseCase open = mock(OpenPointAccountUseCase.class);
    private final AuthEventsListener listener = new AuthEventsListener(open, new JsonMapper());

    private static ConsumerRecord<String, String> record(String type, String value, boolean withId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.AUTH_EVENTS, 0, 0L, "1000", value);
        if (withId) {
            record.headers().add(MessageHeaders.ID, MESSAGE_ID.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void userRegistered_opensAccount() {
        listener.onMessage(record(UserRegistered.TYPE,
                "{\"userId\":1000,\"registeredAt\":\"2026-11-23T00:00:00Z\"}", true));

        verify(open).open(MESSAGE_ID, new OpenPointAccountCommand(1000L, Instant.parse("2026-11-23T00:00:00Z")));
    }

    @Test
    void otherEventTypes_areSkipped() {
        listener.onMessage(record("PasswordChanged", "{\"userId\":1000}", true));

        verify(open, never()).open(any(), any());
    }

    @Test
    void brokenMessages_areNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(null, "{\"userId\":1000}", true)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(UserRegistered.TYPE, "null", true)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(UserRegistered.TYPE, "{\"userId\":0}", true)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record(UserRegistered.TYPE, "{\"userId\":1000}", false)))
                .isInstanceOf(NonRetryableMessageException.class);
        verify(open, never()).open(any(), any());
    }
}
