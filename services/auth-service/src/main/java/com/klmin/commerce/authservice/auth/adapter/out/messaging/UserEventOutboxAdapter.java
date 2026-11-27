package com.klmin.commerce.authservice.auth.adapter.out.messaging;

import com.klmin.commerce.authservice.auth.application.port.out.UserEventPort;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.auth.UserRegistered;
import com.klmin.commerce.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 회원 이벤트를 outbox 에 저장한다. 메시지 키 = userId (UUID 문자열) */
@Component
class UserEventOutboxAdapter implements UserEventPort {

    private final OutboxWriter outboxWriter;

    UserEventOutboxAdapter(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    @Override
    public void userRegistered(UUID userId, Instant registeredAt) {
        outboxWriter.append(Topics.AUTH_EVENTS, userId.toString(), UserRegistered.TYPE,
                new UserRegistered(userId, registeredAt));
    }
}
