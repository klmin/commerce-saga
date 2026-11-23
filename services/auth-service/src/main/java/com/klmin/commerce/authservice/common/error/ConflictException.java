package com.klmin.commerce.authservice.common.error;

/** 이미 있는 리소스와 충돌 (409) */
public abstract class ConflictException extends RuntimeException {

    protected ConflictException(String message) {
        super(message);
    }
}
