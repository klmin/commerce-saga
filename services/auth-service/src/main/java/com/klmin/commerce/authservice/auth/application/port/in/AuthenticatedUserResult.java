package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.Set;

/** @param roles 역할 이름 ({@code USER}, {@code ADMIN}) */
public record AuthenticatedUserResult(long userId, Set<String> roles) {

    public AuthenticatedUserResult {
        roles = Set.copyOf(roles);
    }
}
