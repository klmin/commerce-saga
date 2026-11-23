package com.klmin.commerce.orderservice.common.error;

/** 형식은 맞지만 지금 상태와 맞지 않아 처리할 수 없는 요청 (422). 기능별 예외가 상속한다 */
public abstract class UnprocessableRequestException extends RuntimeException {

    protected UnprocessableRequestException(String message) {
        super(message);
    }
}
