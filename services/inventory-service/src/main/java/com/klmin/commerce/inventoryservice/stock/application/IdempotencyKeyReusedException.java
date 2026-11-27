package com.klmin.commerce.inventoryservice.stock.application;

import com.klmin.commerce.inventoryservice.common.error.UnprocessableRequestException;

/** 같은 Idempotency-Key 로 내용이 다른 재고 조정을 보냄 (422) */
public class IdempotencyKeyReusedException extends UnprocessableRequestException {

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was already used for a different stock adjustment");
    }
}
