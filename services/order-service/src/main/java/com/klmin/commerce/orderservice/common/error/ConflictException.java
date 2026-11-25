package com.klmin.commerce.orderservice.common.error;

import java.util.Map;

/** 지금 상태와 충돌해 사용자 확인이 필요한 요청 (409). properties 는 Problem Details 에 그대로 싣는다 */
public abstract class ConflictException extends RuntimeException {

    protected ConflictException(String message) {
        super(message);
    }

    public abstract Map<String, Object> properties();
}
