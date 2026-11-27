package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.Set;
import java.util.UUID;

/** @param roles 역할 이름 ({@code USER}, {@code ADMIN}) */
public record AuthenticatedUserResult(UUID userId, Set<String> roles) {

    public AuthenticatedUserResult {
        roles = Set.copyOf(roles);
    }
}
