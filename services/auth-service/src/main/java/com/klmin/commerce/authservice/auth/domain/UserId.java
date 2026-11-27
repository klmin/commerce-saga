package com.klmin.commerce.authservice.auth.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 회원 id (UUIDv7, ADR 0018). 가입할 때 앱이 만든다. access token 의 {@code sub} 와 다른 서비스의 userId 가 이 값의 문자열이다
 */
public record UserId(UUID value) {

    public UserId {
        Objects.requireNonNull(value, "userId");
    }

    public static UserId of(UUID value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
