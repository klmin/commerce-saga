package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.UUID;

public interface PayUseCase {

    /**
     * PG 에 승인을 요청하고 결과를 기록·응답한다 (기록 + 응답 outbox = 한 트랜잭션).
     * 같은 messageId 는 한 번만 처리한다(재전달은 응답도 다시 보내지 않음).
     * 다른 메시지로 같은 주문이 다시 오면 PG 를 다시 부르지 않고 기록된 결과를 다시 응답한다.
     */
    void pay(UUID messageId, PayCommand command);
}
