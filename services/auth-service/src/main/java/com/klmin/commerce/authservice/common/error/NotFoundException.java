package com.klmin.commerce.authservice.common.error;

/** 없는 리소스 (404). 기능별 예외가 상속한다 */
public abstract class NotFoundException extends RuntimeException {

    protected NotFoundException(String message) {
        super(message);
    }
}
