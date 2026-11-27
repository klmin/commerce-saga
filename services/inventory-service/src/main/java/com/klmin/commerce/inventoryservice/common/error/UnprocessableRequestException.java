package com.klmin.commerce.inventoryservice.common.error;

/** 형식은 맞지만 처리할 수 없는 요청 (422). 예: 이미 다른 내용에 쓴 Idempotency-Key */
public class UnprocessableRequestException extends RuntimeException {

    public UnprocessableRequestException(String message) {
        super(message);
    }
}
