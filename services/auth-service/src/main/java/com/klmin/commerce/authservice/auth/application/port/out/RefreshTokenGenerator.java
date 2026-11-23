package com.klmin.commerce.authservice.auth.application.port.out;

import com.klmin.commerce.authservice.auth.domain.RefreshToken;

public interface RefreshTokenGenerator {

    /** 새 family 의 첫 토큰 */
    RefreshToken newFamily();

    /** 같은 family 의 다음 토큰 (secret 만 새로) */
    RefreshToken next(String familyId);
}
