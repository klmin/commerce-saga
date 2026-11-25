package com.klmin.commerce.searchservice.common.error;

/** 의존하는 저장소·시스템이 응답하지 않는다 (503). 원인은 로그에만 남기고 응답에는 넣지 않는다 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
