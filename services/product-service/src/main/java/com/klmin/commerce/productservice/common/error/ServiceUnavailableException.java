package com.klmin.commerce.productservice.common.error;

/** 의존하는 외부 시스템이 응답하지 않음 (503). 잠시 뒤 다시 시도하면 된다 */
public abstract class ServiceUnavailableException extends RuntimeException {

    protected ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
