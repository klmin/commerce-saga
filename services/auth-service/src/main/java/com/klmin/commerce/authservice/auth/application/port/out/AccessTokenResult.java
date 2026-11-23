package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;

public record AccessTokenResult(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "AccessTokenResult[expiresAt=" + expiresAt + "]";
    }
}
