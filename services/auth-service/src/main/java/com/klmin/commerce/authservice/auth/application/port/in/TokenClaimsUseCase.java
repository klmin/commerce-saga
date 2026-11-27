package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.UUID;

/**
 * SAS 가 토큰(access·ID)을 만들 때마다 사용자의 지금 역할을 읽는다 (ADR 0017).
 * 로그인 때뿐 아니라 refresh 때도 불려, 역할 변경·계정 정지가 access token 수명 안에 반영된다(ADR 0008 과 같은 규칙).
 */
public interface TokenClaimsUseCase {

    /** @throws com.klmin.commerce.authservice.auth.application.InvalidCredentialsException 지워졌거나 정지된 사용자 */
    AuthenticatedUserResult currentClaims(UUID userId);
}
