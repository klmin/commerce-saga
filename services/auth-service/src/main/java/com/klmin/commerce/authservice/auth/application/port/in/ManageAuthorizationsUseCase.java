package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.UUID;

/** SAS 인가 기록(인가 코드·access·refresh·ID 토큰, {@code oauth2_authorization}) 정리 (ADR 0017) */
public interface ManageAuthorizationsUseCase {

    /** SSO 로그아웃: 이 사용자의 인가 기록을 모두 지운다. 그 refresh token 으로는 더 갱신할 수 없다 */
    void revokeAll(UUID userId);

    /** 모든 토큰이 끝난 지 보존 기간이 지난 인가 기록을 지운다 @return 지운 행 수 */
    int cleanupExpired();
}
