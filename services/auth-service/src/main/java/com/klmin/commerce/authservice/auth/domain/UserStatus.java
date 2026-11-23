package com.klmin.commerce.authservice.auth.domain;

public enum UserStatus {
    ACTIVE,
    /** 정지. 로그인·refresh 가 거부된다 */
    SUSPENDED
}
