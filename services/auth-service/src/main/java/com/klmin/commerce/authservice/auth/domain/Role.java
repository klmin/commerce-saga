package com.klmin.commerce.authservice.auth.domain;

/** 권한은 역할만 쓴다 (ADR 0008). access token 의 {@code roles} 클레임에 이름 그대로 들어간다 */
public enum Role {
    USER,
    ADMIN
}
