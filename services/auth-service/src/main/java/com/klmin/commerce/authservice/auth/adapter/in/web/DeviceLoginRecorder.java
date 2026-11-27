package com.klmin.commerce.authservice.auth.adapter.in.web;

import com.klmin.commerce.authservice.auth.adapter.in.oauth.UserPrincipal;
import com.klmin.commerce.authservice.auth.application.port.in.ManageDevicesUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 로그인(폼 로그인·가입 후 로그인)한 SSO 세션을 기기로 남기고 기기 id 를 세션에 둔다 (후속 5-6).
 * 로그인 체인의 세션 전략에서 세션 고정 방지(세션 id 교체) 다음에 돈다: 바뀐 세션 id 를 남긴다
 */
@Component
class DeviceLoginRecorder implements SessionAuthenticationStrategy {

    private final ManageDevicesUseCase devices;

    DeviceLoginRecorder(ManageDevicesUseCase devices) {
        this.devices = devices;
    }

    @Override
    public void onAuthentication(Authentication authentication, HttpServletRequest request,
                                 HttpServletResponse response) {
        Optional<UUID> found = UserPrincipal.userId(authentication.getName());
        if (found.isEmpty()) {
            return; // 우리 로그인 폼의 사용자가 아니다
        }
        UUID userId = found.get();
        HttpSession session = request.getSession();
        String deviceId = devices.register(userId, session.getId(), request.getHeader(HttpHeaders.USER_AGENT));
        session.setAttribute(DeviceSession.ATTRIBUTE, deviceId);
    }
}
