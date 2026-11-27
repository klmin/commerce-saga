package com.klmin.commerce.paymentservice.payment.adapter.in.messaging;

import com.klmin.commerce.contract.MessageHeaders;
import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.CancelPayment;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.paymentservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentCommandListenerTest {

    static final UUID USER_1 = UUID.fromString("01900000-0000-7000-8001-000000000001");

    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final PayUseCase pay = mock(PayUseCase.class);
    private final CancelPaymentUseCase cancel = mock(CancelPaymentUseCase.class);
    private final PaymentCommandListener listener = new PaymentCommandListener(pay, cancel, new JsonMapper());

    private static ConsumerRecord<String, String> record(String id, String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.PAYMENT_COMMAND, 0, 0L, "k", value);
        if (id != null) {
            record.headers().add(MessageHeaders.ID, id.getBytes(StandardCharsets.UTF_8));
        }
        if (type != null) {
            record.headers().add(MessageHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static String body(UUID orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"userId\":\"" + USER_1 + "\",\"amount\":1500}";
    }

    @Test
    void pay_passesMessageIdAndCommand() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(MESSAGE_ID.toString(), Pay.TYPE, body(orderId)));

        verify(pay).pay(MESSAGE_ID, new PayCommand(orderId, USER_1, 1500L));
    }

    @Test
    void cancelPayment_passesMessageIdAndCommand() {
        UUID orderId = UUID.randomUUID();

        listener.onMessage(record(MESSAGE_ID.toString(), CancelPayment.TYPE, body(orderId)));

        verify(cancel).cancel(MESSAGE_ID, new CancelPaymentCommand(orderId, USER_1, 1500L));
        verifyNoInteractions(pay);
    }

    @Test
    void cancelPayment_withNonPositiveAmount_isNonRetryable() {
        String zero = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + USER_1 + "\",\"amount\":0}";

        assertThatThrownBy(() -> listener.onMessage(record(MESSAGE_ID.toString(), CancelPayment.TYPE, zero)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(cancel);
    }

    @Test
    void cancelPayment_withoutUserId_isNonRetryable() {
        String noUser = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"amount\":1500}";

        assertThatThrownBy(() -> listener.onMessage(record(MESSAGE_ID.toString(), CancelPayment.TYPE, noUser)))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(cancel);
    }

    @Test
    void missingOrInvalidIdHeader_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(null, Pay.TYPE, body(UUID.randomUUID()))))
                .isInstanceOf(NonRetryableMessageException.class);
        assertThatThrownBy(() -> listener.onMessage(record("not-a-uuid", Pay.TYPE, body(UUID.randomUUID()))))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(pay);
    }

    @Test
    void unknownType_isNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record(MESSAGE_ID.toString(), "Refund", body(UUID.randomUUID()))))
                .isInstanceOf(NonRetryableMessageException.class);
        verifyNoInteractions(pay, cancel);
    }
}
