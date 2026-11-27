package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import java.util.Optional;
import java.util.UUID;

/**
 * SAS principal 이름 ↔ userId. 우리 로그인 폼({@link PasswordAuthenticationProvider})은 principal 이름을 userId 의
 * UUID 문자열(소문자 표준 표기)로 둔다. 그 이름이 그대로 토큰의 {@code sub} 와 인가 기록의 {@code principal_name} 이 된다 (ADR 0018)
 */
public final class UserPrincipal {

    private UserPrincipal() {
    }

    public static String name(UUID userId) {
        return userId.toString();
    }

    /**
     * 우리 로그인 폼이 만든 principal 이름이면 userId. 아니면(UUID 가 아니거나 표준 표기가 아니면) 비어 있다.
     * {@link UUID#fromString} 은 "1-1-1-1-1" 같은 줄인 표기도 받아 다른 문자열이 같은 사용자가 되므로 표준 표기만 받는다
     */
    public static Optional<UUID> userId(String principalName) {
        if (principalName == null) {
            return Optional.empty();
        }
        try {
            UUID userId = UUID.fromString(principalName);
            return userId.toString().equals(principalName) ? Optional.of(userId) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
