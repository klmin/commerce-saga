package com.klmin.commerce.productservice.common.error;

/** 도메인 규칙 위반 (400) */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
