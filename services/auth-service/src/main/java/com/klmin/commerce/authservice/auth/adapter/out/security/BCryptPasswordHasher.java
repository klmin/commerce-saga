package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.klmin.commerce.authservice.auth.application.port.out.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    // 없는 아이디로 로그인할 때 비교할 해시. 실제 해시와 같은 비용이라 응답 시간이 같다
    private final String dummyHash = encoder.encode(UUID.randomUUID().toString());

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && encoder.matches(rawPassword, passwordHash);
    }

    @Override
    public void matchAgainstDummy(String rawPassword) {
        encoder.matches(rawPassword == null ? "" : rawPassword, dummyHash);
    }
}
