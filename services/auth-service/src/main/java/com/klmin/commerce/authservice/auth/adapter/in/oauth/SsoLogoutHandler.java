package com.klmin.commerce.authservice.auth.adapter.in.oauth;

import com.klmin.commerce.authservice.auth.adapter.in.web.DeviceSession;
import com.klmin.commerce.authservice.auth.application.port.in.ManageAuthorizationsUseCase;
import com.klmin.commerce.authservice.auth.application.port.in.ManageDevicesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.stereotype.Component;

/**
 * OIDC 로그아웃({@code /connect/logout}) 때 이 기기(브라우저)의 인가 기록을 지운다 (ADR 0017 "SSO 로그아웃", 후속 5-6).
 * SSO 세션은 SAS 의 기본 로그아웃 처리가 끝낸다. 인가 기록까지 지워야 이 브라우저의 다른 화면(클라이언트)이 들고 있던
 * refresh token 으로도 더 갱신하지 못한다. 다른 기기는 그대로 둔다(다른 기기 로그아웃은 기기 화면에서).
 * 기기 id 가 없는 세션(기기를 남기기 전에 로그인한 세션)이면 예전처럼 그 사용자의 인가 기록을 모두 지운다
 */
@Component
public class SsoLogoutHandler implements LogoutHandler {

    private final ManageAuthorizationsUseCase authorizations;
    private final ManageDevicesUseCase devices;

    SsoLogoutHandler(ManageAuthorizationsUseCase authorizations, ManageDevicesUseCase devices) {
        this.authorizations = authorizations;
        this.devices = devices;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        Authentication user = authentication instanceof OidcLogoutAuthenticationToken logout
                && logout.getPrincipal() instanceof Authentication principal ? principal : authentication;
        if (user == null || !user.isAuthenticated()) {
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(user.getName());
        } catch (NumberFormatException ignored) {
            return; // 우리 로그인 폼으로 로그인한 사용자가 아니다(지울 기록 없음)
        }
        String deviceId = DeviceSession.currentDeviceId(request);
        if (deviceId == null) {
            authorizations.revokeAll(userId);
        } else {
            devices.loggedOut(userId, deviceId);
        }
    }
}
