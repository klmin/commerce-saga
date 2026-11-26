package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;

/** SAS 인가 기록 저장소({@code oauth2_authorization}) */
public interface AuthorizationRecordPort {

    /** @param principalName SAS 의 principal 이름 = userId 문자열 */
    int deleteByPrincipal(String principalName);

    /** 들어 있는 토큰이 모두 {@code cutoff} 전에 끝난 기록 */
    int deleteExpiredBefore(Instant cutoff);
}
