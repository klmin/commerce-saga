package com.klmin.commerce.authservice.auth.application.port.in;

import java.util.List;

/**
 * 로그인 기기 관리 (후속 5-6, ADR 0017). 기기 하나 = 브라우저 하나의 SSO 로그인 세션({@code AUTH_SESSION}).
 * 그 세션에서 로그인한 화면들(클라이언트)의 인가 기록(refresh token)을 기기에 묶는다.
 * 기기는 SSO 세션이 살아 있거나 그 기기의 refresh token 이 살아 있는 동안 보인다(화면은 SSO 세션이 끝나도 refresh 로 로그인을 이어간다)
 */
public interface ManageDevicesUseCase {

    /** 로그인(또는 가입 후 로그인)한 SSO 세션을 기기로 남긴다 @return 새 기기 id (세션에 둔다) */
    String register(long userId, String sessionId, String userAgent);

    /** 이 사용자의 기기. 지금 기기가 맨 앞, 그다음 최근 활동 순 */
    List<DeviceResult> devices(long userId, String currentDeviceId);

    /** 다른 기기 하나를 로그아웃: SSO 세션과 그 기기의 인가 기록을 지운다. 지금 기기는 로그아웃(OIDC)으로 */
    void logout(long userId, String deviceId, String currentDeviceId);

    /** 지금 기기를 뺀 모든 기기를 로그아웃 @return 로그아웃한 기기 수 */
    int logoutOthers(long userId, String currentDeviceId);

    /** 이 기기가 OIDC 로그아웃했다(SSO 세션은 SAS 가 끝낸다): 이 기기의 인가 기록을 지우고 기기를 잊는다 */
    void loggedOut(long userId, String deviceId);
}
