package com.klmin.commerce.authservice.auth.application.port.out;

public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);

    /** 없는 아이디로 로그인할 때도 해시 비교만큼 시간을 쓴다 (응답 시간으로 아이디 존재를 알 수 없게) */
    void matchAgainstDummy(String rawPassword);
}
