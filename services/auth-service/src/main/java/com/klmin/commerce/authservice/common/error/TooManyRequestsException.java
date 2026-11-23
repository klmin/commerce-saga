package com.klmin.commerce.authservice.common.error;

import java.time.Duration;

/** 요청 횟수 제한 (429). {@code retryAfter} 는 Retry-After 헤더로 내보낸다 */
public abstract class TooManyRequestsException extends RuntimeException {

    private final Duration retryAfter;

    protected TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
