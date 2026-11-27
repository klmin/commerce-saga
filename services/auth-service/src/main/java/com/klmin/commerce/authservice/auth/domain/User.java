package com.klmin.commerce.authservice.auth.domain;

import com.klmin.commerce.authservice.common.error.DomainValidationException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 회원 애그리거트. 비밀번호는 해시만 가진다(원문 검증은 {@link PasswordPolicy}).
 *
 * @param id 가입할 때 앱이 만든 UUIDv7 (ADR 0018)
 */
public record User(UserId id, String loginId, String passwordHash, Set<Role> roles, UserStatus status,
                   Instant createdAt, Instant updatedAt) {

    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9_]{4,30}");

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(loginId, "loginId");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("사용자는 역할이 하나 이상 있어야 한다");
        }
        roles = Set.copyOf(roles);
    }

    /** 새 회원. 역할은 USER 하나 */
    public static User register(UserId id, String loginId, String passwordHash, Instant now) {
        String normalized = normalizeLoginId(loginId);
        if (!LOGIN_ID.matcher(normalized).matches()) {
            throw new DomainValidationException("아이디는 영문 소문자·숫자·밑줄 4~30자입니다.");
        }
        return new User(id, normalized, passwordHash, EnumSet.of(Role.USER), UserStatus.ACTIVE, now, now);
    }

    /** 아이디는 대소문자를 구분하지 않는다 (로그인할 때도 같은 규칙으로 바꿔 찾는다) */
    public static String normalizeLoginId(String loginId) {
        return loginId == null ? "" : loginId.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
