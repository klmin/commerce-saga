package com.klmin.commerce.paymentservice.payment.application.port.in;

import java.util.UUID;

public interface CancelPaymentUseCase {

    /**
     * 결제 취소 요청을 기록하고 PaymentCancelled 로 응답한다 (기록 + 응답 outbox = 한 트랜잭션, PG 는 부르지 않음).
     * 승인 상태이거나 나중에 PG 조회로 승인이 확인되면 보정 작업이 PG 환불까지 한다.
     * 같은 messageId 는 한 번만 처리하고, 같은 주문의 취소가 다시 오면 같은 응답을 다시 보낸다
     */
    void cancel(UUID messageId, CancelPaymentCommand command);
}
