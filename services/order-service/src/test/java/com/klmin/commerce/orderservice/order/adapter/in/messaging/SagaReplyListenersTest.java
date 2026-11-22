package com.klmin.commerce.orderservice.order.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.inventory.StockReleased;
import com.klmin.commerce.contract.inventory.StockReservationFailed;
import com.klmin.commerce.contract.inventory.StockReserved;
import com.klmin.commerce.contract.payment.PaymentApproved;
import com.klmin.commerce.contract.payment.PaymentFailed;
import com.klmin.commerce.contract.point.PointRefunded;
import com.klmin.commerce.contract.point.PointUseFailed;
import com.klmin.commerce.contract.point.PointUsed;
import com.klmin.commerce.orderservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.orderservice.order.application.port.in.HandleSagaReplyUseCase;
import com.klmin.commerce.orderservice.order.application.port.in.SagaReplyCommand;
import com.klmin.commerce.orderservice.order.domain.SagaEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SagaReplyListenersTest {

    private final HandleSagaReplyUseCase useCase = mock(HandleSagaReplyUseCase.class);
    private final JsonMapper json = new JsonMapper();
    private final InventoryReplyListener inventory = new InventoryReplyListener(useCase, json);
    private final PointReplyListener point = new PointReplyListener(useCase, json);
    private final PaymentReplyListener payment = new PaymentReplyListener(useCase, json);
    private final UUID orderId = UUID.randomUUID();

    private static ConsumerRecord<String, String> record(String topic, String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "k", value);
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private String ok() {
        return "{\"orderId\":\"" + orderId + "\"}";
    }

    private String failed(String reason) {
        return "{\"orderId\":\"" + orderId + "\",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void inventoryReplies() {
        inventory.onMessage(record(Topics.INVENTORY_REPLY, StockReserved.TYPE, ok()));
        inventory.onMessage(record(Topics.INVENTORY_REPLY, StockReservationFailed.TYPE, failed("out")));
        inventory.onMessage(record(Topics.INVENTORY_REPLY, StockReleased.TYPE, ok()));

        verify(useCase).handle(SagaReplyCommand.of(orderId, SagaEventType.STOCK_RESERVED));
        verify(useCase).handle(new SagaReplyCommand(orderId, SagaEventType.STOCK_RESERVATION_FAILED, "out"));
        verify(useCase).handle(SagaReplyCommand.of(orderId, SagaEventType.STOCK_RELEASED));
    }

    @Test
    void pointReplies() {
        point.onMessage(record(Topics.POINT_REPLY, PointUsed.TYPE, ok()));
        point.onMessage(record(Topics.POINT_REPLY, PointUseFailed.TYPE, failed("insufficient")));
        point.onMessage(record(Topics.POINT_REPLY, PointRefunded.TYPE, ok()));

        verify(useCase).handle(SagaReplyCommand.of(orderId, SagaEventType.POINT_USED));
        verify(useCase).handle(new SagaReplyCommand(orderId, SagaEventType.POINT_USE_FAILED, "insufficient"));
        verify(useCase).handle(SagaReplyCommand.of(orderId, SagaEventType.POINT_REFUNDED));
    }

    @Test
    void paymentReplies() {
        payment.onMessage(record(Topics.PAYMENT_REPLY, PaymentApproved.TYPE,
                "{\"orderId\":\"" + orderId + "\",\"paymentId\":\"" + UUID.randomUUID() + "\"}"));
        payment.onMessage(record(Topics.PAYMENT_REPLY, PaymentFailed.TYPE, failed("declined")));

        verify(useCase).handle(SagaReplyCommand.of(orderId, SagaEventType.PAYMENT_APPROVED));
        verify(useCase).handle(new SagaReplyCommand(orderId, SagaEventType.PAYMENT_FAILED, "declined"));
    }

    @Test
    void invalidMessages_areNonRetryable_onEveryReplyTopic() {
        assertNonRetryable(inventory::onMessage, Topics.INVENTORY_REPLY, StockReserved.TYPE);
        assertNonRetryable(point::onMessage, Topics.POINT_REPLY, PointUsed.TYPE);
        assertNonRetryable(payment::onMessage, Topics.PAYMENT_REPLY, PaymentFailed.TYPE);
        verifyNoInteractions(useCase);
    }

    private void assertNonRetryable(Consumer<ConsumerRecord<String, String>> listener, String topic, String type) {
        assertThatThrownBy(() -> listener.accept(record(topic, type, "null")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.accept(record(topic, type, null)))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.accept(record(topic, type, "{\"reason\":\"x\"}")))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.accept(record(topic, "Unknown", ok())))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.accept(record(topic, null, ok())))
                .isInstanceOf(NonRetryableMessageException.class);
        ConsumerRecord<String, String> nullValueHeader = record(topic, null, ok());
        nullValueHeader.headers().add(MessageHeaders.TYPE, null);
        assertThatThrownBy(() -> listener.accept(nullValueHeader))
                .isInstanceOf(NonRetryableMessageException.class);
    }
}
