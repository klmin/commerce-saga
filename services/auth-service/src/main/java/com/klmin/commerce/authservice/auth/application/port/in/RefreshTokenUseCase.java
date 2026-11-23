package com.klmin.commerce.authservice.auth.application.port.in;

public interface RefreshTokenUseCase {

    /** @param rawRefreshToken 쿠키 값 ({@code familyId.secret}) */
    TokenResult refresh(String rawRefreshToken);
}
