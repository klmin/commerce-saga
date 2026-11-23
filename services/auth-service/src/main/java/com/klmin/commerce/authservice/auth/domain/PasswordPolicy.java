package com.klmin.commerce.authservice.auth.domain;

import com.klmin.commerce.authservice.common.error.DomainValidationException;

/**
 * 비밀번호 원문 규칙. 길이만 본다(NIST SP 800-63B: 조합 규칙보다 길이).
 * 최대 64자는 BCrypt 가 앞 72바이트만 쓰기 때문이다 (UTF-8 로 72바이트를 넘으면 거부).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;
    private static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String rawPassword) {
        if (rawPassword == null
                || rawPassword.codePointCount(0, rawPassword.length()) < MIN_LENGTH
                || rawPassword.codePointCount(0, rawPassword.length()) > MAX_LENGTH
                || rawPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new DomainValidationException("비밀번호는 8~64자입니다.");
        }
    }
}
