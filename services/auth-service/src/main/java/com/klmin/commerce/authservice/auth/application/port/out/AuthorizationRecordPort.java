package com.klmin.commerce.authservice.auth.application.port.out;

import java.time.Instant;
import java.util.Map;

/** SAS 인가 기록 저장소({@code oauth2_authorization}) */
public interface AuthorizationRecordPort {

    /** @param principalName SAS 의 principal 이름 = userId 문자열 */
    int deleteByPrincipal(String principalName);

    /** 한 기기(SSO 세션)에서 받은 인가 기록 */
    int deleteByDevice(String principalName, String deviceId);

    /** refresh token 이 {@code now} 뒤에도 살아 있는 인가 기록의 기기별 마지막 토큰 발급 시각 */
    Map<String, Instant> liveDevices(String principalName, Instant now);

    /** 들어 있는 토큰이 모두 {@code cutoff} 전에 끝난 기록 */
    int deleteExpiredBefore(Instant cutoff);
}
