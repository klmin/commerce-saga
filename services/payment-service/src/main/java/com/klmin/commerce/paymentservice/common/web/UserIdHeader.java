package com.klmin.commerce.paymentservice.common.web;

/** 1~3단계 사용자 식별 헤더. 4단계에서 JWT subject 로 바뀐다 */
public final class UserIdHeader {

    public static final String NAME = "X-User-Id";

    private UserIdHeader() {
    }
}
