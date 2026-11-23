package com.klmin.commerce.authservice.auth.application.port.in;

public interface LogoutUseCase {

    /** 이 기기만. 토큰이 없거나 이미 끝났어도 조용히 끝난다 */
    void logout(String rawRefreshToken);

    /** 이 사용자의 모든 기기 */
    void logoutAll(long userId);
}
