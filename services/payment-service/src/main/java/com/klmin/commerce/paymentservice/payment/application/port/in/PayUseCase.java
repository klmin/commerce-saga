package com.klmin.commerce.paymentservice.payment.application.port.in;

public interface PayUseCase {

    /**
     * PG 에 승인을 요청하고 결과를 기록·응답한다 (기록 + 응답 outbox = 한 트랜잭션).
     * 같은 주문으로 다시 호출되면 PG 를 다시 부르지 않고 기록된 결과를 다시 응답한다.
     */
    void pay(PayCommand command);
}
