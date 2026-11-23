package com.klmin.commerce.pointservice.point.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.auth.UserRegistered;
import com.klmin.commerce.pointservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountCommand;
import com.klmin.commerce.pointservice.point.application.port.in.OpenPointAccountUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * auth.events 소비 (UserRegistered → 포인트 계정). 이벤트 토픽이라 소비자가 여럿일 수 있어 관심 없는 타입은 건너뛴다.
 * type 헤더가 없거나, 본문·id 헤더·userId 가 이상하면 재시도해도 같으므로 DLT (KafkaErrorHandlingConfig).
 */
@Component
class AuthEventsListener {

    private static final Logger log = LoggerFactory.getLogger(AuthEventsListener.class);

    private final OpenPointAccountUseCase openPointAccountUseCase;
    private final JsonMapper json;

    AuthEventsListener(OpenPointAccountUseCase openPointAccountUseCase, JsonMapper json) {
        this.openPointAccountUseCase = openPointAccountUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.AUTH_EVENTS)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = Messages.type(record);
        if (type == null || type.isBlank()) {
            throw new NonRetryableMessageException("type header missing: " + Messages.describe(record));
        }
        if (!UserRegistered.TYPE.equals(type)) {
            log.debug("관심 없는 auth 이벤트 건너뜀: {} ({})", type, Messages.describe(record));
            return;
        }
        UserRegistered event = Messages.read(json, record, UserRegistered.class);
        if (event.userId() <= 0) {
            throw new NonRetryableMessageException("invalid userId " + event.userId() + ": " + Messages.describe(record));
        }
        openPointAccountUseCase.open(Messages.messageId(record),
                new OpenPointAccountCommand(event.userId(), event.registeredAt()));
    }
}
