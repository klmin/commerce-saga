package com.klmin.commerce.orderservice.common.error;

/** 의존하는 다른 서비스가 응답하지 않아 지금은 처리할 수 없음 (503). 상태를 바꾸지 않았으므로 다시 시도해도 된다 */
public abstract class ServiceUnavailableException extends RuntimeException {

    protected ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
