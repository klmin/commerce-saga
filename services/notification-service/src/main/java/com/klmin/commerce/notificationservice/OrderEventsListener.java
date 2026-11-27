package com.klmin.commerce.notificationservice;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.order.OrderCancelled;
import com.klmin.commerce.contract.order.OrderCompleted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * order.events 구독 (코레오그래피). 주문 완료·취소를 사용자 알림으로 남긴다 (실제 발송은 로그로 대신한다).
 * <p>
 * order-service 는 이 서비스를 모른다. 이 서비스가 내려가 있어도 주문은 완료되고, 다시 뜨면 밀린 이벤트를 처리한다.
 * 같은 이벤트가 다시 와도 (order_id, type) 유니크로 알림은 한 번만 만든다. 관심 없는 이벤트 타입은 건너뛰고,
 * 관심 있는 타입의 본문이 잘못되면 DLT 로 보존한다 (KafkaErrorHandlingConfig).
 */
@Component
class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final NotificationRepository repository;
    private final JsonMapper json;

    OrderEventsListener(NotificationRepository repository, JsonMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS)
    void onMessage(ConsumerRecord<String, String> record) {
        String type = type(record);
        // 관심 없는 타입은 건너뛰지만, type 헤더 자체가 없거나 비었으면 메시지 이상이라 DLT 로 보존한다
        if (type == null || type.isBlank()) {
            throw new NonRetryableMessageException("type header missing: " + describe(record));
        }
        if (OrderCompleted.TYPE.equals(type)) {
            OrderCompleted e = read(record, OrderCompleted.class);
            notify(requireOrderId(e.orderId(), record), requireUserId(e.userId(), record), type,
                    "주문이 완료되었습니다. 결제 " + e.paidAmount() + "원, 포인트 " + e.pointAmount() + "P 사용");
        } else if (OrderCancelled.TYPE.equals(type)) {
            OrderCancelled e = read(record, OrderCancelled.class);
            notify(requireOrderId(e.orderId(), record), requireUserId(e.userId(), record), type, "주문이 취소되었습니다.");
        }
    }

    private void notify(UUID orderId, UUID userId, String type, String message) {
        if (repository.insertIfAbsent(orderId, userId, type, message)) {
            // 실제 발송(메일·푸시) 대신 로그. 사용자 식별자만 남기고 개인정보는 남기지 않는다
            log.info("알림 발송: {} (orderId={}, userId={})", type, orderId, userId);
        } else {
            log.info("중복 이벤트, 알림 생략: {} (orderId={})", type, orderId);
        }
    }

    private <T> T read(ConsumerRecord<String, String> record, Class<T> type) {
        T value = record.value() == null ? null : json.readValue(record.value(), type);
        if (value == null) {
            throw new NonRetryableMessageException("empty payload: " + describe(record));
        }
        return value;
    }

    private static UUID requireOrderId(UUID orderId, ConsumerRecord<?, ?> record) {
        if (orderId == null) {
            throw new NonRetryableMessageException("orderId missing: " + describe(record));
        }
        return orderId;
    }

    // 사용자 ID 가 잘못된 알림은 조회할 수 없고, (order_id, type) 유니크 때문에 나중에 바로잡을 수도 없다
    // UUID 형식이 아닌 값은 본문 역직렬화(JacksonException)에서 이미 DLT 로 간다
    private static UUID requireUserId(UUID userId, ConsumerRecord<?, ?> record) {
        if (userId == null) {
            throw new NonRetryableMessageException("userId missing: " + describe(record));
        }
        return userId;
    }

    private static String type(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(MessageHeaders.TYPE);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String describe(ConsumerRecord<?, ?> record) {
        return "topic=" + record.topic() + ", partition=" + record.partition() + ", offset=" + record.offset();
    }
}
