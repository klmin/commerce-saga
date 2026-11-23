package com.klmin.commerce.authservice.auth.adapter.out.security;

import com.klmin.commerce.authservice.auth.application.port.out.RefreshTokenGenerator;
import com.klmin.commerce.authservice.auth.domain.RefreshToken;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** secret 은 32바이트 난수 (base64url). familyId 는 랜덤 UUID (시각이 드러나는 v7 대신 v4) */
@Component
class SecureRandomRefreshTokenGenerator implements RefreshTokenGenerator {

    private static final int SECRET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    @Override
    public RefreshToken newFamily() {
        return next(UUID.randomUUID().toString());
    }

    @Override
    public RefreshToken next(String familyId) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return new RefreshToken(familyId, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
    }
}
