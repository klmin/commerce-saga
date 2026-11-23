package com.klmin.commerce.orderservice.order.application;

import com.klmin.commerce.orderservice.common.error.UnprocessableRequestException;

/** 같은 Idempotency-Key 로 내용이 다른 주문을 보냄 (422) */
public class IdempotencyKeyReusedException extends UnprocessableRequestException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used for a different order request");
    }
}
