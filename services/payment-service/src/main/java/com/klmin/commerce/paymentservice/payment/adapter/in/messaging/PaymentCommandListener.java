package com.klmin.commerce.paymentservice.payment.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.CancelPayment;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.paymentservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.CancelPaymentUseCase;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment.command 소비 (Pay, CancelPayment). 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 같은 메시지의 재전달은 processed_message(id 헤더)로, 내용이 같은 다른 메시지는 주문별 결제 기록
 * (payments.order_id 유니크)과 PG 멱등 키로 걸러져 두 번 결제되지 않는다 (ADR 0006).
 */
@Component
class PaymentCommandListener {

    private final PayUseCase payUseCase;
    private final CancelPaymentUseCase cancelPaymentUseCase;
    private final JsonMapper json;

    PaymentCommandListener(PayUseCase payUseCase, CancelPaymentUseCase cancelPaymentUseCase, JsonMapper json) {
        this.payUseCase = payUseCase;
        this.cancelPaymentUseCase = cancelPaymentUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMAND)
    void onMessage(ConsumerRecord<String, String> record) {
        // 커맨드 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        String type = Messages.type(record);
        switch (type) {
            case Pay.TYPE -> {
                Pay command = Messages.read(json, record, Pay.class);
                payUseCase.pay(Messages.messageId(record), new PayCommand(
                        Messages.requireOrderId(command.orderId(), record), command.userId(), command.amount()));
            }
            case CancelPayment.TYPE -> {
                CancelPayment command = Messages.read(json, record, CancelPayment.class);
                // 결제 단계까지 온 주문만 취소가 온다(결제 금액 > 0). 아니면 재시도해도 같으므로 DLT
                if (command.amount() <= 0) {
                    throw new NonRetryableMessageException("invalid CancelPayment amount: " + Messages.describe(record));
                }
                cancelPaymentUseCase.cancel(Messages.messageId(record), new CancelPaymentCommand(
                        Messages.requireOrderId(command.orderId(), record), command.userId(), command.amount()));
            }
            case null, default -> throw new NonRetryableMessageException(
                    "unknown type " + type + ": " + Messages.describe(record));
        }
    }
}
