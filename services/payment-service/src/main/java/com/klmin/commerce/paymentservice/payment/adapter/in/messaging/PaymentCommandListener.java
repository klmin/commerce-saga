package com.klmin.commerce.paymentservice.payment.adapter.in.messaging;

import com.klmin.commerce.contract.Topics;
import com.klmin.commerce.contract.payment.Pay;
import com.klmin.commerce.paymentservice.common.error.NonRetryableMessageException;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayCommand;
import com.klmin.commerce.paymentservice.payment.application.port.in.PayUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment.command 소비 (Pay). 처리 중 일시 오류는 복구될 때까지 재시도하고,
 * 재시도해도 같은 오류(본문·헤더 이상)는 DLT 에 보존된다 (KafkaErrorHandlingConfig).
 * 중복 커맨드는 주문별 결제 기록(payments.order_id 유니크)과 PG 멱등 키로 걸러져 두 번 결제되지 않는다.
 */
@Component
class PaymentCommandListener {

    private final PayUseCase payUseCase;
    private final JsonMapper json;

    PaymentCommandListener(PayUseCase payUseCase, JsonMapper json) {
        this.payUseCase = payUseCase;
        this.json = json;
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMAND)
    void onMessage(ConsumerRecord<String, String> record) {
        // 커맨드 토픽에는 계약에 정한 타입만 온다. 헤더가 없거나 모르는 타입이면 DLT 로 보존한다
        String type = Messages.type(record);
        if (!Pay.TYPE.equals(type)) {
            throw new NonRetryableMessageException("unknown type " + type + ": " + Messages.describe(record));
        }
        Pay command = Messages.read(json, record, Pay.class);
        payUseCase.pay(new PayCommand(Messages.requireOrderId(command.orderId(), record),
                command.userId(), command.amount()));
    }
}
